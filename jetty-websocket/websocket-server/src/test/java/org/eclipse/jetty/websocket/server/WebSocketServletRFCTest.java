//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.eclipse.jetty.websocket.server.helper.RFCServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 * <p>
 * This test serves a different purpose than than the {@link WebSocketMessageRFC6455Test}, and {@link WebSocketParserRFC6455Test} tests.
 */
@RunWith(AdvancedRunner.class)
public class WebSocketServletRFCTest
{
    private static Generator generator = new UnitGenerator();
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new RFCServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    private void enableStacks(Class<?> clazz, boolean enabled)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(!enabled);
    }

    /**
     * Test that aggregation of binary frames into a single message occurs
     */
    @Test
    public void testBinaryAggregate() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate binary frames
            byte buf1[] = new byte[128];
            byte buf2[] = new byte[128];
            byte buf3[] = new byte[128];

            Arrays.fill(buf1,(byte)0xAA);
            Arrays.fill(buf2,(byte)0xBB);
            Arrays.fill(buf3,(byte)0xCC);

            WebSocketFrame bin;

            bin = WebSocketFrame.binary(buf1).setFin(false);

            client.write(bin); // write buf1 (fin=false)

            bin = new WebSocketFrame(OpCode.CONTINUATION).setPayload(buf2).setFin(false);

            client.write(bin); // write buf2 (fin=false)

            bin = new WebSocketFrame(OpCode.CONTINUATION).setPayload(buf3).setFin(true);

            client.write(bin); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,1000);
            Frame binmsg = capture.getFrames().poll();
            int expectedSize = buf1.length + buf2.length + buf3.length;
            Assert.assertThat("BinaryFrame.payloadLength",binmsg.getPayloadLength(),is(expectedSize));

            int aaCount = 0;
            int bbCount = 0;
            int ccCount = 0;

            ByteBuffer echod = binmsg.getPayload();
            while (echod.remaining() >= 1)
            {
                byte b = echod.get();
                switch (b)
                {
                    case (byte)0xAA:
                        aaCount++;
                        break;
                    case (byte)0xBB:
                        bbCount++;
                        break;
                    case (byte)0xCC:
                        ccCount++;
                        break;
                    default:
                        Assert.fail(String.format("Encountered invalid byte 0x%02X",(byte)(0xFF & b)));
                }
            }
            Assert.assertThat("Echoed data count for 0xAA",aaCount,is(buf1.length));
            Assert.assertThat("Echoed data count for 0xBB",bbCount,is(buf2.length));
            Assert.assertThat("Echoed data count for 0xCC",ccCount,is(buf3.length));
        }
        finally
        {
            client.close();
        }
    }

    @Test(expected = NotUtf8Exception.class)
    public void testDetectBadUTF8()
    {
        byte buf[] = new byte[]
        { (byte)0xC2, (byte)0xC3 };

        Utf8StringBuilder utf = new Utf8StringBuilder();
        utf.append(buf,0,buf.length);
    }

    /**
     * Test the requirement of issuing
     */
    @Test
    public void testEcho() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(WebSocketFrame.text(msg));

            // Read frame (hopefully text frame)
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame tf = capture.getFrames().poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     */
    @Test
    public void testInternalError() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            client.write(WebSocketFrame.text("CRASH"));

            // Read frame (hopefully close frame)
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            Frame cf = capture.getFrames().poll();
            CloseInfo close = new CloseInfo(cf);
            Assert.assertThat("Close Frame.status code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all lowercase headers.
     */
    @Test
    public void testLowercaseUpgrade() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(client.getRequestPath()).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(client.getRequestHost()).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("connection: upgrade\r\n");
            req.append("sec-websocket-key: ").append(client.getRequestWebSocketKey()).append("\r\n");
            req.append("sec-websocket-origin: ").append(client.getRequestWebSocketOrigin()).append("\r\n");
            req.append("sec-websocket-protocol: echo\r\n");
            req.append("sec-websocket-version: 13\r\n");
            req.append("\r\n");
            client.writeRaw(req.toString());

            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(WebSocketFrame.text(msg));

            // Read frame (hopefully text frame)
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame tf = capture.getFrames().poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("Should be moved to Fuzzer")
    public void testMaxBinarySize() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Choose a size for a single frame larger than the
            // server side policy
            int dataSize = 1024 * 100;
            byte buf[] = new byte[dataSize];
            Arrays.fill(buf,(byte)0x44);

            WebSocketFrame bin = WebSocketFrame.binary(buf).setFin(true);
            ByteBuffer bb = generator.generate(bin);
            try
            {
                client.writeRaw(bb);
                Assert.fail("Write should have failed due to terminated connection");
            }
            catch (SocketException e)
            {
                Assert.assertThat("Exception",e.getMessage(),containsString("Broken pipe"));
            }

            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = capture.getFrames().poll();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.MESSAGE_TOO_LARGE));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("Should be moved to Fuzzer")
    public void testMaxTextSize() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Choose a size for a single frame larger than the
            // server side policy
            int dataSize = 1024 * 100;
            byte buf[] = new byte[dataSize];
            Arrays.fill(buf,(byte)'z');

            WebSocketFrame text = WebSocketFrame.text().setPayload(buf).setFin(true);
            ByteBuffer bb = generator.generate(text);
            try
            {
                client.writeRaw(bb);
                Assert.fail("Write should have failed due to terminated connection");
            }
            catch (SocketException e)
            {
                Assert.assertThat("Exception",e.getMessage(),containsString("Broken pipe"));
            }

            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = capture.getFrames().poll();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.MESSAGE_TOO_LARGE));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testTextNotUTF8() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        enableStacks(Parser.class,false);

        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte buf[] = new byte[]
            { (byte)0xC2, (byte)0xC3 };

            WebSocketFrame txt = WebSocketFrame.text().setPayload(buf);
            ByteBuffer bb = generator.generate(txt);
            client.writeRaw(bb);

            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = capture.getFrames().poll();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.BAD_PAYLOAD));
        }
        finally
        {
            // Reenable Long Stacks from Parser
            enableStacks(Parser.class,true);
            client.close();
        }
    }

    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all uppercase headers.
     */
    @Test
    public void testUppercaseUpgrade() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(client.getRequestPath()).append(" HTTP/1.1\r\n");
            req.append("HOST: ").append(client.getRequestHost()).append("\r\n");
            req.append("UPGRADE: WEBSOCKET\r\n");
            req.append("CONNECTION: UPGRADE\r\n");
            req.append("SEC-WEBSOCKET-KEY: ").append(client.getRequestWebSocketKey()).append("\r\n");
            req.append("SEC-WEBSOCKET-ORIGIN: ").append(client.getRequestWebSocketOrigin()).append("\r\n");
            req.append("SEC-WEBSOCKET-PROTOCOL: ECHO\r\n");
            req.append("SEC-WEBSOCKET-VERSION: 13\r\n");
            req.append("\r\n");
            client.writeRaw(req.toString());

            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(WebSocketFrame.text(msg));

            // Read frame (hopefully text frame)
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame tf = capture.getFrames().poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }
}
