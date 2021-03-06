/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.qpid.proton.systemtests;

import static org.apache.qpid.proton.systemtests.TestLoggingHelper.bold;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Logger;

import org.junit.Test;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Sasl.SaslOutcome;

public class SaslTest extends EngineTestBase
{
    private static final Logger LOGGER = Logger.getLogger(SaslTest.class.getName());
    private static final String TESTMECH1 = "TESTMECH1";
    private static final String TESTMECH2 = "TESTMECH2";
    private static final byte[] CHALLENGE_BYTES = "challenge-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE_BYTES = "response-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ADDITIONAL_DATA_BYTES = "additional-data-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    public void testSaslHostnamePropagationAndRetrieval() throws Exception
    {
        LOGGER.fine(bold("======== About to create transports"));

        getClient().transport = Proton.transport();
        ProtocolTracerEnabler.setProtocolTracer(getClient().transport, TestLoggingHelper.CLIENT_PREFIX);

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();

        // Set the server hostname we are connecting to from the client
        String hostname = "my-remote-host-123";
        clientSasl.setRemoteHostname(hostname);

        // Verify we can't get the hostname on the client
        try
        {
            clientSasl.getHostname();
            fail("should have throw IllegalStateException");
        }
        catch (IllegalStateException ise)
        {
            // expected
        }

        getServer().transport = Proton.transport();
        ProtocolTracerEnabler.setProtocolTracer(getServer().transport, "            " + TestLoggingHelper.SERVER_PREFIX);

        // Configure the server to do ANONYMOUS
        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();
        serverSasl.setMechanisms("ANONYMOUS");

        // Verify we can't set the hostname on the server
        try
        {
            serverSasl.setRemoteHostname("some-other-host");
            fail("should have throw IllegalStateException");
        }
        catch (IllegalStateException ise)
        {
            // expected
        }

        assertNull(serverSasl.getHostname());
        assertArrayEquals(new String[0], clientSasl.getRemoteMechanisms());

        pumpClientToServer();
        pumpServerToClient();

        // Verify we got the mechs, set the chosen mech, and verify the
        // server still doesnt know the hostname set/requested by the client
        assertArrayEquals(new String[] {"ANONYMOUS"} , clientSasl.getRemoteMechanisms());
        clientSasl.setMechanisms("ANONYMOUS");
        assertNull(serverSasl.getHostname());

        pumpClientToServer();

        // Verify the server now knows that the client set the hostname field
        assertEquals(hostname, serverSasl.getHostname());
    }

    /** 5.3.2 SASL Negotiation. */
    @Test
    public void testSaslNegotiation() throws Exception
    {
        getClient().transport = Proton.transport();
        getServer().transport = Proton.transport();

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());

        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();
        serverSasl.setMechanisms(TESTMECH1, TESTMECH2);
        assertEquals("Server should not yet know the remote's chosen mechanism.",
                     0,
                     serverSasl.getRemoteMechanisms().length);

        pumpClientToServer();
        pumpServerToClient();

        assertArrayEquals("Client should now know the server's mechanisms.",
                          new String[]{TESTMECH1, TESTMECH2},
                          clientSasl.getRemoteMechanisms());
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        clientSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();

        assertArrayEquals("Server should now know the client's chosen mechanism.",
                          new String[]{TESTMECH1},
                          serverSasl.getRemoteMechanisms());

        serverSasl.send(CHALLENGE_BYTES, 0, CHALLENGE_BYTES.length);

        pumpServerToClient();

        byte[] clientReceivedChallengeBytes = new byte[clientSasl.pending()];
        clientSasl.recv(clientReceivedChallengeBytes, 0, clientReceivedChallengeBytes.length);

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        assertArrayEquals("Client should now know the server's challenge",
                          CHALLENGE_BYTES,
                          clientReceivedChallengeBytes);

        clientSasl.send(RESPONSE_BYTES, 0, RESPONSE_BYTES.length);

        pumpClientToServer();

        byte[] serverReceivedResponseBytes = new byte[serverSasl.pending()];
        serverSasl.recv(serverReceivedResponseBytes, 0, serverReceivedResponseBytes.length);

        assertArrayEquals("Server should now know the client's response",
                          RESPONSE_BYTES,
                          serverReceivedResponseBytes);

        serverSasl.done(SaslOutcome.PN_SASL_OK);
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_OK, clientSasl.getOutcome());
    }

    /** 5.3.2 SASL Negotiation. ...challenge/response step can occur zero or more times*/
    @Test
    public void testOptionalChallengeResponseStepOmitted() throws Exception
    {
        getClient().transport = Proton.transport();
        getServer().transport = Proton.transport();

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());

        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();
        serverSasl.setMechanisms(TESTMECH1);
        assertEquals("Server should not yet know the remote's chosen mechanism.",
                     0,
                     serverSasl.getRemoteMechanisms().length);

        pumpClientToServer();
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        clientSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();

        serverSasl.done(SaslOutcome.PN_SASL_OK);
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_OK, clientSasl.getOutcome());
    }

    /**
     *  5.3.3.5 The additional-data field carries additional data on successful authentication outcome as specified
     *  by the SASL specification [RFC4422].
     */
    @Test
    public void testOutcomeAdditionalData() throws Exception
    {
        getClient().transport = Proton.transport();
        getServer().transport = Proton.transport();

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());

        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();
        serverSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        clientSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();

        serverSasl.send(CHALLENGE_BYTES, 0, CHALLENGE_BYTES.length);

        pumpServerToClient();

        byte[] clientReceivedChallengeBytes = new byte[clientSasl.pending()];
        clientSasl.recv(clientReceivedChallengeBytes, 0, clientReceivedChallengeBytes.length);

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        clientSasl.send(RESPONSE_BYTES, 0, RESPONSE_BYTES.length);

        pumpClientToServer();

        byte[] serverReceivedResponseBytes = new byte[serverSasl.pending()];
        serverSasl.recv(serverReceivedResponseBytes, 0, serverReceivedResponseBytes.length);

        serverSasl.send(ADDITIONAL_DATA_BYTES, 0, ADDITIONAL_DATA_BYTES.length);
        serverSasl.done(SaslOutcome.PN_SASL_OK);
        pumpServerToClient();

        byte[] clientReceivedAdditionalDataBytes = new byte[clientSasl.pending()];
        clientSasl.recv(clientReceivedAdditionalDataBytes, 0, clientReceivedAdditionalDataBytes.length);

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_OK, clientSasl.getOutcome());
        assertArrayEquals("Client should now know the serrver's additional-data",
                          ADDITIONAL_DATA_BYTES,
                          clientReceivedAdditionalDataBytes);
    }

    /**
     *  5.3.3.6 Connection authentication failed due to an unspecified problem with the supplied credentials.
     */
    @Test
    public void testAuthenticationFails() throws Exception
    {
        getClient().transport = Proton.transport();
        getServer().transport = Proton.transport();

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());

        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();
        serverSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        clientSasl.setMechanisms(TESTMECH1);

        pumpClientToServer();

        serverSasl.done(SaslOutcome.PN_SASL_AUTH);
        pumpServerToClient();
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_AUTH, clientSasl.getOutcome());

    }

    /*
     * Test that transports configured to do so are able to perform SASL process where frames are
     * exchanged larger than the 512byte min-max-frame-size.
     */
    @Test
    public void testSaslNegotiationWithConfiguredLargerFrameSize() throws Exception
    {
        final byte[] largeInitialResponseBytesOrig = fillBytes("initialResponse", 1431);
        final byte[] largeChallengeBytesOrig = fillBytes("challenge", 1375);
        final byte[] largeResponseBytesOrig = fillBytes("response", 1282);
        final byte[] largeAdditionalBytesOrig = fillBytes("additionalData", 1529);

        getClient().transport = Proton.transport();
        getServer().transport = Proton.transport();

        // Configure transports to allow for larger initial frame sizes
        getClient().transport.setInitialRemoteMaxFrameSize(2048);
        getServer().transport.setInitialRemoteMaxFrameSize(2048);

        Sasl clientSasl = getClient().transport.sasl();
        clientSasl.client();

        Sasl serverSasl = getServer().transport.sasl();
        serverSasl.server();

        // Negotiate the mech
        serverSasl.setMechanisms(TESTMECH1, TESTMECH2);

        pumpClientToServer();
        pumpServerToClient();

        assertArrayEquals("Client should now know the server's mechanisms.", new String[] { TESTMECH1, TESTMECH2 },
                clientSasl.getRemoteMechanisms());
        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());

        // Select a mech, send large initial response along with it in sasl-init, verify server receives it
        clientSasl.setMechanisms(TESTMECH1);
        byte[] initialResponseBytes = Arrays.copyOf(largeInitialResponseBytesOrig, largeInitialResponseBytesOrig.length);
        clientSasl.send(initialResponseBytes, 0, initialResponseBytes.length);

        pumpClientToServer();

        assertArrayEquals("Server should now know the client's chosen mechanism.", new String[] { TESTMECH1 },
                serverSasl.getRemoteMechanisms());

        byte[] serverReceivedInitialResponseBytes = new byte[serverSasl.pending()];
        serverSasl.recv(serverReceivedInitialResponseBytes, 0, serverReceivedInitialResponseBytes.length);

        assertArrayEquals("Server should now know the clients initial response", largeInitialResponseBytesOrig,
                serverReceivedInitialResponseBytes);

        // Send a large challenge in a sasl-challenge, verify client receives it
        byte[] challengeBytes = Arrays.copyOf(largeChallengeBytesOrig, largeChallengeBytesOrig.length);
        serverSasl.send(challengeBytes, 0, challengeBytes.length);

        pumpServerToClient();

        byte[] clientReceivedChallengeBytes = new byte[clientSasl.pending()];
        clientSasl.recv(clientReceivedChallengeBytes, 0, clientReceivedChallengeBytes.length);

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_NONE, clientSasl.getOutcome());
        assertArrayEquals("Client should now know the server's challenge", largeChallengeBytesOrig,
                clientReceivedChallengeBytes);

        // Send a large response in a sasl-response, verify server receives it
        byte[] responseBytes = Arrays.copyOf(largeResponseBytesOrig, largeResponseBytesOrig.length);
        clientSasl.send(responseBytes, 0, responseBytes.length);

        pumpClientToServer();

        byte[] serverReceivedResponseBytes = new byte[serverSasl.pending()];
        serverSasl.recv(serverReceivedResponseBytes, 0, serverReceivedResponseBytes.length);

        assertArrayEquals("Server should now know the client's response", largeResponseBytesOrig, serverReceivedResponseBytes);

        // Send an outcome with large additional data in a sasl-outcome, verify client receives it
        byte[] additionalBytes = Arrays.copyOf(largeAdditionalBytesOrig, largeAdditionalBytesOrig.length);
        serverSasl.send(additionalBytes, 0, additionalBytes.length);
        serverSasl.done(SaslOutcome.PN_SASL_OK);
        pumpServerToClient();

        assertEquals("Unexpected SASL outcome at client", SaslOutcome.PN_SASL_OK, clientSasl.getOutcome());

        byte[] clientReceivedAdditionalBytes = new byte[clientSasl.pending()];
        clientSasl.recv(clientReceivedAdditionalBytes, 0, clientReceivedAdditionalBytes.length);

        assertArrayEquals("Client should now know the server's outcome additional data", largeAdditionalBytesOrig,
                clientReceivedAdditionalBytes);
    }

    private byte[] fillBytes(String seedString, int length)
    {
        byte[] seed = seedString.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
        {
            bytes[i] = seed[i % seed.length];
        }

        return bytes;
    }
}
