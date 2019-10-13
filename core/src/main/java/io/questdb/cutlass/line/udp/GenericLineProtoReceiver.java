/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package io.questdb.cutlass.line.udp;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.CairoSecurityContext;
import io.questdb.cutlass.line.CairoLineProtoParser;
import io.questdb.cutlass.line.LineProtoLexer;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.Job;
import io.questdb.network.NetworkFacade;
import io.questdb.std.Misc;
import io.questdb.std.Unsafe;
import io.questdb.std.str.DirectByteCharSequence;

import java.io.Closeable;

public class GenericLineProtoReceiver implements Closeable, Job {
    private static final Log LOG = LogFactory.getLog(GenericLineProtoReceiver.class);

    private final DirectByteCharSequence byteSequence = new DirectByteCharSequence();
    private final LineProtoLexer lexer;
    private final CairoLineProtoParser parser;
    private final NetworkFacade nf;
    private final int bufLen;
    private long fd;
    private int commitRate;
    private long totalCount = 0;
    private long buf;

    public GenericLineProtoReceiver(
            LineUdpReceiverConfiguration receiverCfg,
            CairoEngine engine,
            CairoSecurityContext cairoSecurityContext
    ) {
        nf = receiverCfg.getNetworkFacade();
        fd = nf.socketUdp();
        if (fd < 0) {
            int errno = nf.errno();
            LOG.error().$("cannot open UDP socket [errno=").$(errno).$(']').$();
            throw CairoException.instance(errno).put("Cannot open UDP socket");
        }

        try {
            if (nf.bindUdp(fd, 0, receiverCfg.getPort())) {
                if (nf.join(fd, receiverCfg.getBindIPv4Address(), receiverCfg.getGroupIPv4Address())) {
                    this.commitRate = receiverCfg.getCommitRate();

                    if (receiverCfg.getReceiveBufferSize() != -1 && nf.setRcvBuf(fd, receiverCfg.getReceiveBufferSize()) != 0) {
                        LOG.error().$("cannot set receive buffer size [fd=").$(fd).$(", size=").$(receiverCfg.getReceiveBufferSize()).$(']').$();
                    }

                    this.buf = Unsafe.malloc(this.bufLen = receiverCfg.getMsgBufferSize());

                    lexer = new LineProtoLexer(receiverCfg.getMsgBufferSize());
                    parser = new CairoLineProtoParser(engine, cairoSecurityContext);
                    lexer.withParser(parser);

                    LOG.info()
                            .$("started [fd=").$(fd)
                            .$(", bind=").$(receiverCfg.getBindIPv4Address())
                            .$(", group=").$(receiverCfg.getGroupIPv4Address())
                            .$(", port=").$(receiverCfg.getPort())
                            .$(", commitRate=").$(commitRate)
                            .$(']').$();

                    return;
                }
                int errno = nf.errno();
                LOG.error().$("cannot join group [errno=").$(errno).$(", fd=").$(fd).$(", bind=").$(receiverCfg.getBindIPv4Address()).$(", group=").$(receiverCfg.getGroupIPv4Address()).$(']').$();
                throw CairoException.instance(nf.errno()).put("Cannot join group ").put(receiverCfg.getGroupIPv4Address()).put(" [bindTo=").put(receiverCfg.getBindIPv4Address()).put(']');

            }
            int errno = nf.errno();
            LOG.error().$("cannot bind socket [errno=").$(errno).$(", fd=").$(fd).$(", bind=").$(receiverCfg.getBindIPv4Address()).$(", port=").$(receiverCfg.getPort()).$(']').$();
            throw CairoException.instance(nf.errno()).put("Cannot bind to ").put(receiverCfg.getBindIPv4Address()).put(':').put(receiverCfg.getPort());
        } catch (CairoException e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (fd > -1) {
            if (nf.close(fd) != 0) {
                LOG.error().$("failed to close [fd=").$(fd).$(", errno=").$(nf.errno()).$(']').$();
            } else {
                LOG.info().$("closed [fd=").$(fd).$(']').$();
            }
            if (buf != 0) {
                Unsafe.free(buf, bufLen);
            }
            if (parser != null) {
                parser.commitAll();
                parser.close();
            }

            Misc.free(lexer);
            fd = -1;
        }
    }

    @Override
    public boolean run() {
        boolean ran = false;
        int count;
        while ((count = nf.recv(fd, buf, bufLen)) > 0) {
            byteSequence.of(buf, buf + count);
            lexer.parse(buf, buf + count);
            lexer.parseLast();

            totalCount++;

            if (totalCount > commitRate) {
                totalCount = 0;
                parser.commitAll();
            }

            if (ran) {
                continue;
            }

            ran = true;
        }
        parser.commitAll();
        return ran;
    }
}
