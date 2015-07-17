package com.github.ambry.clustermap;

import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.config.VerifiableProperties;
import java.util.ArrayList;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;


// TestDataNode permits DataNode to be constructed with a null Datacenter.
class TestDataNode extends DataNode {
  public TestDataNode(JSONObject jsonObject, ClusterMapConfig clusterMapConfig)
      throws JSONException {
    super(null, jsonObject, clusterMapConfig);
  }

  @Override
  public void validateDatacenter() {
    // Null OK
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TestDataNode testDataNode = (TestDataNode) o;

    if (!getHostname().equals(testDataNode.getHostname())) {
      return false;
    }
    if (getPort() != testDataNode.getPort()) {
      return false;
    }
    if (getState() != testDataNode.getState()) {
      return false;
    }
    return getRawCapacityInBytes() == testDataNode.getRawCapacityInBytes();
  }
}

/**
 * Tests {@link DataNode} class.
 */
public class DataNodeTest {
  private static final int diskCount = 10;
  private static final long diskCapacityInBytes = 1000 * 1024 * 1024 * 1024L;

  JSONArray getDisks()
      throws JSONException {
    return TestUtils.getJsonArrayDisks(diskCount, "/mnt", HardwareState.AVAILABLE, diskCapacityInBytes);
  }

  @Test
  public void basics()
      throws JSONException {

    JSONObject jsonObject =
        TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 6666, 7666, HardwareState.AVAILABLE, getDisks());
    ClusterMapConfig clusterMapConfig = new ClusterMapConfig(new VerifiableProperties(new Properties()));

    DataNode dataNode = new TestDataNode(jsonObject, clusterMapConfig);

    assertEquals(dataNode.getHostname(), TestUtils.getLocalHost());
    assertEquals(dataNode.getPort(), 6666);
    assertEquals(dataNode.getState(), HardwareState.AVAILABLE);

    assertEquals(dataNode.getDisks().size(), diskCount);
    assertEquals(dataNode.getRawCapacityInBytes(), diskCount * diskCapacityInBytes);

    assertEquals(dataNode.toJSONObject().toString(), jsonObject.toString());
    assertEquals(dataNode, new TestDataNode(dataNode.toJSONObject(), clusterMapConfig));
  }

  public void failValidation(JSONObject jsonObject, ClusterMapConfig clusterMapConfig)
      throws JSONException {
    try {
      new TestDataNode(jsonObject, clusterMapConfig);
      fail("Construction of TestDataNode should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }
  }

  @Test
  public void validation()
      throws JSONException {
    JSONObject jsonObject;
    ClusterMapConfig clusterMapConfig = new ClusterMapConfig(new VerifiableProperties(new Properties()));

    try {
      // Null DataNode
      jsonObject = TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 6666, 7666, HardwareState.AVAILABLE, getDisks());
      new DataNode(null, jsonObject, clusterMapConfig);
      fail("Should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }

    // Bad hostname
    jsonObject = TestUtils.getJsonDataNode("", 6666, 7666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);

    // Bad hostname (http://tools.ietf.org/html/rfc6761 defines 'invalid' top level domain)
    jsonObject = TestUtils.getJsonDataNode("hostname.invalid", 6666, 7666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);

    // Bad port (too small)
    jsonObject = TestUtils.getJsonDataNode(TestUtils.getLocalHost(), -1, 7666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);

    // Bad ssl port (too small)
    jsonObject = TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 6666, -1, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);

    // Bad port (too big)
    jsonObject =
        TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 100 * 1000, 7666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);

    // Bad ssl port (too big)
    jsonObject =
        TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 6666, 100* 1000, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject, clusterMapConfig);
  }

  @Test
  public void testSoftState()
      throws JSONException, InterruptedException {
    JSONObject jsonObject =
        TestUtils.getJsonDataNode(TestUtils.getLocalHost(), 6666, 7666, HardwareState.AVAILABLE, getDisks());
    Properties props = new Properties();
    props.setProperty("clustermap.fixedtimeout.datanode.retry.backoff.ms", Integer.toString(2000));
    ClusterMapConfig clusterMapConfig = new ClusterMapConfig(new VerifiableProperties(props));
    int threshold = clusterMapConfig.clusterMapFixedTimeoutDatanodeErrorThreshold;
    long retryBackoffMs = clusterMapConfig.clusterMapFixedTimeoutDataNodeRetryBackoffMs;

    DataNode dataNode = new TestDataNode(jsonObject, clusterMapConfig);
    for (int i = 0; i < threshold; i++) {
      ensure(dataNode, HardwareState.AVAILABLE);
      dataNode.onNodeTimeout();
    }
    // After threshold number of continuous errors, the resource should be unavailable
    ensure(dataNode, HardwareState.UNAVAILABLE);

    Thread.sleep(retryBackoffMs + 1);
    // If retryBackoffMs has passed, the resource should be available.
    ensure(dataNode, HardwareState.AVAILABLE);

    //A single timeout should make the node unavailable now
    dataNode.onNodeTimeout();
    ensure(dataNode, HardwareState.UNAVAILABLE);

    //A single response should make the node available now
    dataNode.onNodeResponse();
    ensure(dataNode, HardwareState.AVAILABLE);
  }

  void ensure(DataNode dataNode, HardwareState state) {
    assertEquals(dataNode.getState(), state);
    for (DiskId disk : dataNode.getDisks()) {
      assertEquals(disk.getState(), state);
    }
  }
}
