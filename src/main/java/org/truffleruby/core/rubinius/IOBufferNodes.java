/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 *
 * Some of the code in this class is transliterated from C++ code in Rubinius.
 *
 * Copyright (c) 2007-2014, Evan Phoenix and contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of Rubinius nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.truffleruby.core.rubinius;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import jnr.constants.platform.Errno;
import org.truffleruby.Layouts;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.collections.ByteArrayBuilder;
import org.truffleruby.core.exception.ExceptionOperations;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.objects.AllocateObjectNode;

@CoreClass("IO::InternalBuffer")
public abstract class IOBufferNodes {

    private static final int IOBUFFER_SIZE = 32768;
    private static final int STACK_BUF_SZ = 8192;

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public static abstract class AllocateNode extends UnaryCoreMethodNode {

        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        @Specialization
        public DynamicObject allocate(DynamicObject classToAllocate) {
            return allocateNode.allocate(classToAllocate,
                        ByteArrayNodes.createByteArray(coreLibrary().getByteArrayFactory(), RopeBuilder.createRopeBuilder(IOBUFFER_SIZE)),
                        0,
                        IOBUFFER_SIZE);
        }

    }

    @Primitive(name = "iobuffer_unshift", lowerFixnum = { 2, 3 })
    public static abstract class IOBufferUnshiftPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyString(string)")
        public int unshift(DynamicObject ioBuffer, DynamicObject string, int startPosition, int used,
                @Cached("create()") RopeNodes.BytesNode bytesNode) {
            final Rope rope = StringOperations.rope(string);
            final int available = IOBUFFER_SIZE - used;

            final byte[] bytes;
            int written;

            bytes = bytesNode.execute(rope);
            written = rope.byteLength() - startPosition;

            if (written > available) {
                written = available;
            }

            ByteArrayBuilder storage = Layouts.BYTE_ARRAY.getBytes(Layouts.IO_BUFFER.getStorage(ioBuffer));

            // TODO (nirvdrum 08-24-16): Data is copied here - can we do something COW?
            System.arraycopy(bytes, startPosition, storage.getUnsafeBytes(), used, written);

            return written;
        }

    }

    @Primitive(name = "iobuffer_fill")
    public static abstract class IOBufferFillPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public int fill(VirtualFrame frame, DynamicObject ioBuffer, DynamicObject io,
                @Cached("create()") BranchProfile errorProfile) {
            final int fd = Layouts.IO.getDescriptor(io);

            // TODO CS 21-Apr-15 allocating this buffer for each read is crazy
            final byte[] readBuffer = new byte[STACK_BUF_SZ];
            int count = STACK_BUF_SZ;

            if (left(frame, ioBuffer) < count) {
                count = left(frame, ioBuffer);
            }

            int bytesRead = performFill(fd, readBuffer, count);

            if (bytesRead > 0) {
                // Detect if another thread has updated the buffer
                // and now there isn't enough room for this data.
                if (bytesRead > left(frame, ioBuffer)) {
                    errorProfile.enter();
                    throw new RaiseException(coreExceptions().internalError("IO buffer overrun", this));
                }
                final int used = Layouts.IO_BUFFER.getUsed(ioBuffer);
                final ByteArrayBuilder storage = Layouts.BYTE_ARRAY.getBytes(Layouts.IO_BUFFER.getStorage(ioBuffer));
                System.arraycopy(readBuffer, 0, storage.getUnsafeBytes(), used, bytesRead);
                storage.setLength(used + bytesRead);
                Layouts.IO_BUFFER.setUsed(ioBuffer, used + bytesRead);
            }

            return bytesRead;
        }

        @TruffleBoundary
        private int performFill(int fd, byte[] readBuffer, int count) {
            int bytesRead;
            while (true) {
                bytesRead = getContext().getThreadManager().runBlockingSystemCallUntilResult(this,
                        () -> posix().read(fd, readBuffer, count));

                if (bytesRead == -1) {
                    final int errno = posix().errno();

                    if (errno == Errno.ECONNRESET.intValue() || errno == Errno.ETIMEDOUT.intValue()) {
                        // Treat as seeing eof
                        bytesRead = 0;
                        break;
                    } else if (errno == Errno.EAGAIN.intValue()) {
                        //if (!state -> check_async(calling_environment))
                        //    return NULL;
                        //io -> ensure_open(state);
                        getContext().getSafepointManager().pollFromBlockingCall(this);
                        continue;
                    } else {
                        throw new RaiseException(ExceptionOperations.createSystemCallError(coreLibrary().getErrnoClass(Errno.valueOf(errno)), nil(), null, errno));
                    }
                } else {
                    break;
                }
            }
            return bytesRead;
        }

        private int left(VirtualFrame frame, DynamicObject ioBuffer) {
            final int total = Layouts.IO_BUFFER.getTotal(ioBuffer);
            final int used = Layouts.IO_BUFFER.getUsed(ioBuffer);
            return total - used;
        }

    }

}
