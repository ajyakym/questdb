/*
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2016. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.misc;

import com.nfsdb.ex.NetworkError;
import com.nfsdb.ex.NumericException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class Net {
    private Net() {
    }

    public native static long accept(long fd);

    public native static boolean bind(long fd, int address, int port);

    public static boolean bind(long fd, CharSequence address, int port) {
        return bind(fd, parseIPv4(address), port);
    }

    public native static void close(long fd);

    public static native int configureNonBlocking(long fd);

    public native static void listen(long fd, int backlog);

    public static void main(String[] args) {
        Os.init();
        long fd;
        System.out.println(fd = Net.socketTcp(true));
        System.out.println(Net.bind(fd, 0, 9000));
        listen(fd, 1024);
        Net.configureNonBlocking(fd);
        System.out.println(Net.setRcvBuf(fd, 4096));
        System.out.println(Net.setSndBuf(fd, 4096));
        accept(fd);
    }

    public static native int recv(long fd, long ptr, int len);

    public static native int send(long fd, long ptr, int len);

    public native static int setRcvBuf(long fd, int size);

    public native static int setSndBuf(long fd, int size);

    public native static long socketTcp(boolean blocking);

    @SuppressFBWarnings("LEST_LOST_EXCEPTION_STACK_TRACE")
    private static int parseIPv4(CharSequence address) {
        int ip = 0;
        int count = 0;
        int lo = 0;
        int hi;
        try {
            while ((hi = Chars.indexOf(address, lo, '.')) > -1) {
                int n = Numbers.parseInt(address, lo, hi);
                ip = (ip << 8) | n;
                count++;
                lo = hi + 1;
            }

            if (count != 3) {
                throw new NetworkError("Invalid ip address: " + address);
            }

            return (ip << 8) | Numbers.parseInt(address, lo, address.length());
        } catch (NumericException e) {
            throw new NetworkError("Invalid ip address: " + address);
        }
    }
}
