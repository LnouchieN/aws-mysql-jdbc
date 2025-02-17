/*
 * AWS JDBC Driver for MySQL
 * Copyright Amazon.com Inc. or affiliates.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License, version 2.0, as published by the
 * Free Software Foundation.
 *
 * This program is also distributed with certain software (including but not
 * limited to OpenSSL) that is licensed under separate terms, as designated in a
 * particular file or component or in included license documentation. The
 * authors of this program hereby grant you an additional permission to link the
 * program and your derivative works with the separately licensed software that
 * they have included with MySQL.
 *
 * Without limiting anything contained in the foregoing, this file, which is
 * part of this connector, is also subject to the Universal FOSS Exception,
 * version 1.0, a copy of which can be found at
 * http://oss.oracle.com/licenses/universal-foss-exception.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License, version 2.0,
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301  USA
 */

package com.mysql.cj.jdbc.ha.ca;

import com.mysql.cj.conf.ConnectionUrl;
import com.mysql.cj.conf.HostInfo;
import com.mysql.cj.jdbc.ConnectionImpl;
import com.mysql.cj.log.Log;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClusterAwareReaderFailoverHandlerTest class.
 * */
public class ClusterAwareReaderFailoverHandlerTest {
  static final int numTestUrls = 6;
  static final String testUrl1 = "jdbc:mysql:aws://writer-1:1234/";
  static final String testUrl2 = "jdbc:mysql:aws://reader-1:2345/";
  static final String testUrl3 = "jdbc:mysql:aws://reader-2:3456/";
  static final String testUrl4 = "jdbc:mysql:aws://reader-3:4567/";
  static final String testUrl5 = "jdbc:mysql:aws://reader-4:5678/";
  static final String testUrl6 = "jdbc:mysql:aws://reader-5:6789/";
  final Log mockLog = Mockito.mock(Log.class);

  @Test
  public void testFailover() throws SQLException {
    // original host list: [active writer, active reader, current connection (reader), active
    // reader, down reader, active reader]
    // priority order by index (the subsets will be shuffled): [[1, 3, 5], 0, [2, 4]]
    // connection attempts are made in pairs using the above list
    // expected test result: successful connection for host at index 4
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    final ConnectionProvider mockConnProvider = Mockito.mock(ConnectionProvider.class);
    final ConnectionImpl mockConnection = Mockito.mock(ConnectionImpl.class);
    final List<HostInfo> hosts = getHostsFromTestUrls(6);
    final int currentHostIndex = 2;
    final int successHostIndex = 4;
    for (int i = 0; i < hosts.size(); i++) {
      if (i != successHostIndex) {
        when(mockConnProvider.connect(hosts.get(i))).thenThrow(new SQLException());
      } else {
        when(mockConnProvider.connect(hosts.get(i))).thenReturn(mockConnection);
      }
    }

    final Set<String> downHosts = new HashSet<>();
    final List<Integer> downHostIndexes = Arrays.asList(2, 4);
    for (int hostIndex : downHostIndexes) {
      downHosts.add(hosts.get(hostIndex).getHostPortPair());
    }
    when(mockTopologyService.getDownHosts()).thenReturn(downHosts);

    final ReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(mockTopologyService, mockConnProvider, mockLog);
    final ConnectionAttemptResult result = target.failover(hosts, hosts.get(currentHostIndex));

    assertTrue(result.isSuccess());
    assertSame(mockConnection, result.getConnection());
    assertEquals(successHostIndex, result.getConnectionIndex());

    final HostInfo successHost = hosts.get(successHostIndex);
    verify(mockTopologyService, atLeast(4)).addToDownHostList(any());
    verify(mockTopologyService, never()).addToDownHostList(eq(successHost));
    verify(mockTopologyService, times(1)).removeFromDownHostList(eq(successHost));
  }

  private List<HostInfo> getHostsFromTestUrls(int numHosts) {
    final List<String> urlList =
        Arrays.asList(testUrl1, testUrl2, testUrl3, testUrl4, testUrl5, testUrl6);
    final List<HostInfo> hosts = new ArrayList<>();
    if (numHosts < 0 || numHosts > numTestUrls) {
      numHosts = numTestUrls;
    }
    for (int i = 0; i < numHosts; i++) {
      ConnectionUrl connUrl =
          ConnectionUrl.getConnectionUrlInstance(urlList.get(i), new Properties());
      hosts.add(connUrl.getMainHost());
    }
    return hosts;
  }

  @Test
  public void testFailover_timeout() throws SQLException {
    // original host list: [active writer, active reader, current connection (reader), active
    // reader, down reader, active reader]
    // priority order by index (the subsets will be shuffled): [[1, 3, 5], 0, [2, 4]]
    // connection attempts are made in pairs using the above list
    // expected test result: failure to get reader since process is limited to 5s and each attempt to connect takes 20s
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    final ConnectionProvider mockConnProvider = Mockito.mock(ConnectionProvider.class);
    final ConnectionImpl mockConnection = Mockito.mock(ConnectionImpl.class);
    final List<HostInfo> hosts = getHostsFromTestUrls(6);
    final int currentHostIndex = 2;
    for (int i = 0; i < hosts.size(); i++) {
        when(mockConnProvider.connect(hosts.get(i)))
                .thenAnswer(
                        (Answer<ConnectionImpl>)
                                invocation -> {
                                  Thread.sleep(20000);
                                  return mockConnection;
                                });
    }

    final Set<String> downHosts = new HashSet<>();
    final List<Integer> downHostIndexes = Arrays.asList(2, 4);
    for (int hostIndex : downHostIndexes) {
      downHosts.add(hosts.get(hostIndex).getHostPortPair());
    }
    when(mockTopologyService.getDownHosts()).thenReturn(downHosts);

    final ReaderFailoverHandler target =
            new ClusterAwareReaderFailoverHandler(mockTopologyService, mockConnProvider, 5000, 30000, mockLog);
    final ConnectionAttemptResult result = target.failover(hosts, hosts.get(currentHostIndex));

    assertFalse(result.isSuccess());
    assertNull(result.getConnection());
    assertEquals(ClusterAwareConnectionProxy.NO_CONNECTION_INDEX, result.getConnectionIndex());
  }

  @Test
  public void testFailover_nullOrEmptyHostList() throws SQLException {
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    final ClusterAwareReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(
            mockTopologyService, Mockito.mock(ConnectionProvider.class), mockLog);
    final HostInfo currentHost = new HostInfo(null, "writer", 1234, null, null);

    ConnectionAttemptResult result = target.failover(null, currentHost);
    assertFalse(result.isSuccess());
    assertNull(result.getConnection());
    assertEquals(ClusterAwareConnectionProxy.NO_CONNECTION_INDEX, result.getConnectionIndex());
    verify(mockTopologyService, times(1)).addToDownHostList(eq(currentHost));

    final List<HostInfo> hosts = new ArrayList<>();
    result = target.failover(hosts, currentHost);
    assertFalse(result.isSuccess());
    assertNull(result.getConnection());
    assertEquals(ClusterAwareConnectionProxy.NO_CONNECTION_INDEX, result.getConnectionIndex());

    verify(mockTopologyService, times(2)).addToDownHostList(eq(currentHost));
  }

  @Test
  public void testGetReader_connectionSuccess() throws SQLException {
    // even number of connection attempts
    // first connection attempt to return succeeds, second attempt cancelled
    // expected test result: successful connection for host at index 2
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    when(mockTopologyService.getDownHosts()).thenReturn(new HashSet<>());
    final ConnectionImpl mockConnection = Mockito.mock(ConnectionImpl.class);
    final List<HostInfo> hosts = getHostsFromTestUrls(3); // 2 connection attempts (writer not attempted)
    final HostInfo slowHost = hosts.get(1);
    final HostInfo fastHost = hosts.get(2);
    final ConnectionProvider mockConnProvider = Mockito.mock(ConnectionProvider.class);
    when(mockConnProvider.connect(slowHost))
        .thenAnswer(
            (Answer<ConnectionImpl>)
                invocation -> {
                  Thread.sleep(20000);
                  return mockConnection;
                });
    when(mockConnProvider.connect(fastHost)).thenReturn(mockConnection);

    final ReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(mockTopologyService, mockConnProvider, mockLog);
    final ConnectionAttemptResult result = target.getReaderConnection(hosts);

    assertTrue(result.isSuccess());
    assertSame(mockConnection, result.getConnection());
    assertEquals(2, result.getConnectionIndex());

    verify(mockTopologyService, never()).addToDownHostList(any());
    verify(mockTopologyService, times(1)).removeFromDownHostList(eq(fastHost));
  }

  @Test
  public void testGetReader_connectionFailure() throws SQLException {
    // odd number of connection attempts
    // first connection attempt to return fails
    // expected test result: failure to get reader
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    when(mockTopologyService.getDownHosts()).thenReturn(new HashSet<>());
    final ConnectionProvider mockConnProvider = Mockito.mock(ConnectionProvider.class);
    final List<HostInfo> hosts = getHostsFromTestUrls(4); // 3 connection attempts (writer not attempted)
    when(mockConnProvider.connect(any())).thenThrow(new SQLException());

    final int currentHostIndex = 2;

    final ReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(mockTopologyService, mockConnProvider, mockLog);
    final ConnectionAttemptResult result = target.getReaderConnection(hosts);

    assertFalse(result.isSuccess());
    assertNull(result.getConnection());
    assertEquals(ClusterAwareConnectionProxy.NO_CONNECTION_INDEX, result.getConnectionIndex());

    final HostInfo currentHost = hosts.get(currentHostIndex);
    verify(mockTopologyService, atLeastOnce()).addToDownHostList(eq(currentHost));
    verify(mockTopologyService, never())
        .addToDownHostList(
            eq(hosts.get(ClusterAwareConnectionProxy.WRITER_CONNECTION_INDEX)));
  }

  @Test
  public void testGetReader_connectionAttemptsTimeout() throws SQLException {
    // connection attempts time out before they can succeed
    // first connection attempt to return times out
    // expected test result: failure to get reader
    final TopologyService mockTopologyService = Mockito.mock(TopologyService.class);
    when(mockTopologyService.getDownHosts()).thenReturn(new HashSet<>());
    final ConnectionProvider mockProvider = Mockito.mock(ConnectionProvider.class);
    final ConnectionImpl mockConnection = Mockito.mock(ConnectionImpl.class);
    final List<HostInfo> hosts = getHostsFromTestUrls(3); // 2 connection attempts (writer not attempted)
    when(mockProvider.connect(any()))
        .thenAnswer(
            (Answer<ConnectionImpl>)
                invocation -> {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException exception) {
                    // ignore
                  }
                  return mockConnection;
                });

    final ClusterAwareReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(mockTopologyService, mockProvider, 60000, 1000, mockLog);
    final ConnectionAttemptResult result = target.getReaderConnection(hosts);

    assertFalse(result.isSuccess());
    assertNull(result.getConnection());
    assertEquals(ClusterAwareConnectionProxy.NO_CONNECTION_INDEX, result.getConnectionIndex());

    verify(mockTopologyService, never()).addToDownHostList(any());
  }

  @Test
  public void testGetHostTuplesByPriority() {
    final List<HostInfo> originalHosts = getHostsFromTestUrls(6);

    final Set<String> downHosts = new HashSet<>();
    final List<Integer> downHostIndexes = Arrays.asList(2, 4, 5);
    for (int hostIndex : downHostIndexes) {
      downHosts.add(originalHosts.get(hostIndex).getHostPortPair());
    }

    final ClusterAwareReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(
            Mockito.mock(TopologyService.class), Mockito.mock(ConnectionProvider.class), mockLog);
    final List<ClusterAwareReaderFailoverHandler.HostTuple> tuplesByPriority =
        target.getHostTuplesByPriority(originalHosts, downHosts);

    final int activeReaderOriginalIndex = 1;
    final int downReaderOriginalIndex = 5;

    // get new positions of active reader, writer, down reader in tuplesByPriority
    final int activeReaderTupleIndex =
        getHostTupleIndexFromOriginalIndex(activeReaderOriginalIndex, tuplesByPriority);
    final int writerTupleIndex =
        getHostTupleIndexFromOriginalIndex(
            ClusterAwareConnectionProxy.WRITER_CONNECTION_INDEX, tuplesByPriority);
    final int downReaderTupleIndex =
        getHostTupleIndexFromOriginalIndex(downReaderOriginalIndex, tuplesByPriority);

    // assert the following priority ordering: active readers, writer, down readers
    final int numActiveReaders = 2;
    assertTrue(writerTupleIndex > activeReaderTupleIndex);
    assertEquals(numActiveReaders, writerTupleIndex);
    assertTrue(downReaderTupleIndex > writerTupleIndex);
    assertEquals(6, tuplesByPriority.size());
  }

  private int getHostTupleIndexFromOriginalIndex(
      int originalIndex, List<ClusterAwareReaderFailoverHandler.HostTuple> tuples) {
    for (int i = 0; i < tuples.size(); i++) {
      ClusterAwareReaderFailoverHandler.HostTuple tuple = tuples.get(i);
      if (tuple.getIndex() == originalIndex) {
        return i;
      }
    }
    return -1;
  }

  @Test
  public void testGetReaderTuplesByPriority() {
    final List<HostInfo> originalHosts = getHostsFromTestUrls(6);

    final Set<String> downHosts = new HashSet<>();
    final List<Integer> downHostIndexes = Arrays.asList(2, 4, 5);
    for (int hostIndex : downHostIndexes) {
      downHosts.add(originalHosts.get(hostIndex).getHostPortPair());
    }

    final ClusterAwareReaderFailoverHandler target =
        new ClusterAwareReaderFailoverHandler(
            Mockito.mock(TopologyService.class), Mockito.mock(ConnectionProvider.class), mockLog);
    final List<ClusterAwareReaderFailoverHandler.HostTuple> readerTuples =
        target.getReaderTuplesByPriority(originalHosts, downHosts);

    final int activeReaderOriginalIndex = 1;
    final int downReaderOriginalIndex = 5;

    // get new positions of active reader, down reader in readerTuples
    final int activeReaderTupleIndex =
        getHostTupleIndexFromOriginalIndex(activeReaderOriginalIndex, readerTuples);
    final int downReaderTupleIndex =
        getHostTupleIndexFromOriginalIndex(downReaderOriginalIndex, readerTuples);

    // assert the following priority ordering: active readers, down readers
    final int numActiveReaders = 2;
    final ClusterAwareReaderFailoverHandler.HostTuple writerTuple =
        new ClusterAwareReaderFailoverHandler.HostTuple(
            originalHosts.get(ClusterAwareConnectionProxy.WRITER_CONNECTION_INDEX),
            ClusterAwareConnectionProxy.WRITER_CONNECTION_INDEX);
    assertTrue(downReaderTupleIndex > activeReaderTupleIndex);
    assertTrue(downReaderTupleIndex >= numActiveReaders);
    assertFalse(readerTuples.contains(writerTuple));
    assertEquals(5, readerTuples.size());
  }
}
