/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.snapshot;

import static org.apache.hadoop.util.ToolRunner.run;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.SnapshotType;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.master.snapshot.SnapshotManager;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.VerySlowMapReduceTests;
import org.apache.hadoop.hbase.tool.BulkLoadHFilesTool;
import org.apache.hadoop.hbase.util.AbstractHBaseTool;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.HFileTestUtil;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.collect.Lists;

import org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotDescription;
import org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotRegionManifest;

/**
 * Test Export Snapshot Tool
 */
@Ignore // HBASE-24493
@Category({ VerySlowMapReduceTests.class, LargeTests.class })
public class TestExportSnapshot {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestExportSnapshot.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestExportSnapshot.class);

  protected final static HBaseTestingUtil TEST_UTIL = new HBaseTestingUtil();

  protected final static byte[] FAMILY = Bytes.toBytes("cf");

  @Rule
  public final TestName testName = new TestName();

  protected TableName tableName;
  private String emptySnapshotName;
  private String snapshotName;
  private int tableNumFiles;
  private Admin admin;

  public static void setUpBaseConf(Configuration conf) {
    conf.setBoolean(SnapshotManager.HBASE_SNAPSHOT_ENABLED, true);
    conf.setInt("hbase.regionserver.msginterval", 100);
    // If a single node has enough failures (default 3), resource manager will blacklist it.
    // With only 2 nodes and tests injecting faults, we don't want that.
    conf.setInt("mapreduce.job.maxtaskfailures.per.tracker", 100);
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    setUpBaseConf(TEST_UTIL.getConfiguration());
    TEST_UTIL.startMiniCluster(1);
    TEST_UTIL.startMiniMapReduceCluster();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniMapReduceCluster();
    TEST_UTIL.shutdownMiniCluster();
  }

  /**
   * Create a table and take a snapshot of the table used by the export test.
   */
  @Before
  public void setUp() throws Exception {
    this.admin = TEST_UTIL.getAdmin();

    tableName = TableName.valueOf("testtb-" + testName.getMethodName());
    snapshotName = "snaptb0-" + testName.getMethodName();
    emptySnapshotName = "emptySnaptb0-" + testName.getMethodName();

    // create Table
    createTable(this.tableName);

    // Take an empty snapshot
    admin.snapshot(emptySnapshotName, tableName);

    // Add some rows
    SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 50, FAMILY);
    tableNumFiles = admin.getRegions(tableName).size();

    // take a snapshot
    admin.snapshot(snapshotName, tableName);
  }

  protected void createTable(TableName tableName) throws Exception {
    SnapshotTestingUtils.createPreSplitTable(TEST_UTIL, tableName, 2, FAMILY);
  }

  protected interface RegionPredicate {
    boolean evaluate(final RegionInfo regionInfo);
  }

  protected RegionPredicate getBypassRegionPredicate() {
    return null;
  }

  @After
  public void tearDown() throws Exception {
    TEST_UTIL.deleteTable(tableName);
    SnapshotTestingUtils.deleteAllSnapshots(TEST_UTIL.getAdmin());
    SnapshotTestingUtils.deleteArchiveDirectory(TEST_UTIL);
  }

  /**
   * Verify if exported snapshot and copied files matches the original one.
   */
  @Test
  public void testExportFileSystemState() throws Exception {
    testExportFileSystemState(tableName, snapshotName, snapshotName, tableNumFiles);
  }

  @Test
  public void testExportFileSystemStateWithMergeRegion() throws Exception {
    // disable compaction
    admin.compactionSwitch(false,
      admin.getRegionServers().stream().map(a -> a.getServerName()).collect(Collectors.toList()));
    // create Table
    TableName tableName0 = TableName.valueOf("testtb-" + testName.getMethodName() + "-1");
    String snapshotName0 = "snaptb0-" + testName.getMethodName() + "-1";
    admin.createTable(
      TableDescriptorBuilder.newBuilder(tableName0)
        .setColumnFamilies(
          Lists.newArrayList(ColumnFamilyDescriptorBuilder.newBuilder(FAMILY).build()))
        .build(),
      new byte[][] { Bytes.toBytes("2") });
    // put some data
    try (Table table = admin.getConnection().getTable(tableName0)) {
      table.put(new Put(Bytes.toBytes("1")).addColumn(FAMILY, null, Bytes.toBytes("1")));
      table.put(new Put(Bytes.toBytes("2")).addColumn(FAMILY, null, Bytes.toBytes("2")));
    }
    List<RegionInfo> regions = admin.getRegions(tableName0);
    assertEquals(2, regions.size());
    tableNumFiles = regions.size();
    // merge region
    admin.mergeRegionsAsync(new byte[][] { regions.get(0).getEncodedNameAsBytes(),
      regions.get(1).getEncodedNameAsBytes() }, true).get();
    // take a snapshot
    admin.snapshot(snapshotName0, tableName0);
    // export snapshot and verify
    testExportFileSystemState(tableName0, snapshotName0, snapshotName0, tableNumFiles);
    // delete table
    TEST_UTIL.deleteTable(tableName0);
  }

  @Test
  public void testExportFileSystemStateWithSplitRegion() throws Exception {
    // disable compaction
    admin.compactionSwitch(false,
      admin.getRegionServers().stream().map(a -> a.getServerName()).collect(Collectors.toList()));
    // create Table
    TableName splitTableName = TableName.valueOf(testName.getMethodName());
    String splitTableSnap = "snapshot-" + testName.getMethodName();
    admin.createTable(TableDescriptorBuilder.newBuilder(splitTableName).setColumnFamilies(
      Lists.newArrayList(ColumnFamilyDescriptorBuilder.newBuilder(FAMILY).build())).build());

    Path output = TEST_UTIL.getDataTestDir("output/cf");
    TEST_UTIL.getTestFileSystem().mkdirs(output);
    // Create and load a large hfile to ensure the execution time of MR job.
    HFileTestUtil.createHFile(TEST_UTIL.getConfiguration(), TEST_UTIL.getTestFileSystem(),
      new Path(output, "test_file"), FAMILY, Bytes.toBytes("q"), Bytes.toBytes("1"),
      Bytes.toBytes("9"), 9999999);
    BulkLoadHFilesTool tool = new BulkLoadHFilesTool(TEST_UTIL.getConfiguration());
    tool.run(new String[] { output.getParent().toString(), splitTableName.getNameAsString() });

    List<RegionInfo> regions = admin.getRegions(splitTableName);
    assertEquals(1, regions.size());
    tableNumFiles = regions.size();

    // split region
    admin.split(splitTableName, Bytes.toBytes("5"));
    regions = admin.getRegions(splitTableName);
    assertEquals(2, regions.size());

    // take a snapshot
    admin.snapshot(splitTableSnap, splitTableName);
    // export snapshot and verify
    Configuration tmpConf = TEST_UTIL.getConfiguration();
    // Decrease the buffer size of copier to avoid the export task finished shortly
    tmpConf.setInt("snapshot.export.buffer.size", 1);
    // Decrease the maximum files of each mapper to ensure the three files(1 hfile + 2 reference
    // files)
    // copied in different mappers concurrently.
    tmpConf.setInt("snapshot.export.default.map.group", 1);
    testExportFileSystemState(tmpConf, splitTableName, splitTableSnap, splitTableSnap,
      tableNumFiles, TEST_UTIL.getDefaultRootDirPath(), getHdfsDestinationDir(), false, false,
      getBypassRegionPredicate(), true, false);
    // delete table
    TEST_UTIL.deleteTable(splitTableName);
  }

  @Test
  public void testExportFileSystemStateWithSkipTmp() throws Exception {
    TEST_UTIL.getConfiguration().setBoolean(ExportSnapshot.CONF_SKIP_TMP, true);
    try {
      testExportFileSystemState(tableName, snapshotName, snapshotName, tableNumFiles);
    } finally {
      TEST_UTIL.getConfiguration().setBoolean(ExportSnapshot.CONF_SKIP_TMP, false);
    }
  }

  @Test
  public void testEmptyExportFileSystemState() throws Exception {
    testExportFileSystemState(tableName, emptySnapshotName, emptySnapshotName, 0);
  }

  @Test
  public void testConsecutiveExports() throws Exception {
    Path copyDir = getLocalDestinationDir(TEST_UTIL);
    testExportFileSystemState(tableName, snapshotName, snapshotName, tableNumFiles, copyDir, false);
    testExportFileSystemState(tableName, snapshotName, snapshotName, tableNumFiles, copyDir, true);
    removeExportDir(copyDir);
  }

  @Test
  public void testExportWithChecksum() throws Exception {
    // Test different schemes: input scheme is hdfs:// and output scheme is file://
    // The checksum verification will fail
    Path copyLocalDir = getLocalDestinationDir(TEST_UTIL);
    testExportFileSystemState(TEST_UTIL.getConfiguration(), tableName, snapshotName, snapshotName,
      tableNumFiles, TEST_UTIL.getDefaultRootDirPath(), copyLocalDir, false, false,
      getBypassRegionPredicate(), false, true);

    // Test same schemes: input scheme is hdfs:// and output scheme is hdfs://
    // The checksum verification will success
    Path copyHdfsDir = getHdfsDestinationDir();
    testExportFileSystemState(TEST_UTIL.getConfiguration(), tableName, snapshotName, snapshotName,
      tableNumFiles, TEST_UTIL.getDefaultRootDirPath(), copyHdfsDir, false, false,
      getBypassRegionPredicate(), true, true);
  }

  @Test
  public void testExportWithTargetName() throws Exception {
    final String targetName = "testExportWithTargetName";
    testExportFileSystemState(tableName, snapshotName, targetName, tableNumFiles);
  }

  @Test
  public void testExportWithResetTtl() throws Exception {
    String name = "testExportWithResetTtl";
    TableName tableName = TableName.valueOf(name);
    String snapshotName = "snaptb-" + name;
    Long ttl = 100000L;

    try {
      // create Table
      createTable(tableName);
      SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 50, FAMILY);
      int tableNumFiles = admin.getRegions(tableName).size();
      // take a snapshot with TTL
      Map<String, Object> props = new HashMap<>();
      props.put("TTL", ttl);
      admin.snapshot(snapshotName, tableName, props);
      Optional<Long> ttlOpt =
        admin.listSnapshots().stream().filter(s -> s.getName().equals(snapshotName))
          .map(org.apache.hadoop.hbase.client.SnapshotDescription::getTtl).findAny();
      assertTrue(ttlOpt.isPresent());
      assertEquals(ttl, ttlOpt.get());

      testExportFileSystemState(tableName, snapshotName, snapshotName, tableNumFiles,
        getHdfsDestinationDir(), false, true);
    } catch (Exception e) {
      throw e;
    } finally {
      TEST_UTIL.deleteTable(tableName);
    }
  }

  @Test
  public void testExportExpiredSnapshot() throws Exception {
    String name = "testExportExpiredSnapshot";
    TableName tableName = TableName.valueOf(name);
    String snapshotName = "snapshot-" + name;
    createTable(tableName);
    SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 50, FAMILY);
    Map<String, Object> properties = new HashMap<>();
    properties.put("TTL", 10);
    org.apache.hadoop.hbase.client.SnapshotDescription snapshotDescription =
      new org.apache.hadoop.hbase.client.SnapshotDescription(snapshotName, tableName,
        SnapshotType.FLUSH, null, EnvironmentEdgeManager.currentTime(), -1, properties);
    admin.snapshot(snapshotDescription);
    boolean isExist =
      admin.listSnapshots().stream().anyMatch(ele -> snapshotName.equals(ele.getName()));
    assertTrue(isExist);
    int retry = 6;
    while (
      !SnapshotDescriptionUtils.isExpiredSnapshot(snapshotDescription.getTtl(),
        snapshotDescription.getCreationTime(), EnvironmentEdgeManager.currentTime()) && retry > 0
    ) {
      retry--;
      Thread.sleep(10 * 1000);
    }
    boolean isExpiredSnapshot =
      SnapshotDescriptionUtils.isExpiredSnapshot(snapshotDescription.getTtl(),
        snapshotDescription.getCreationTime(), EnvironmentEdgeManager.currentTime());
    assertTrue(isExpiredSnapshot);
    int res = runExportSnapshot(TEST_UTIL.getConfiguration(), snapshotName, snapshotName,
      TEST_UTIL.getDefaultRootDirPath(), getHdfsDestinationDir(), false, false, false, true, true);
    assertTrue(res == AbstractHBaseTool.EXIT_FAILURE);
  }

  private void testExportFileSystemState(final TableName tableName, final String snapshotName,
    final String targetName, int filesExpected) throws Exception {
    testExportFileSystemState(tableName, snapshotName, targetName, filesExpected,
      getHdfsDestinationDir(), false);
  }

  protected void testExportFileSystemState(final TableName tableName, final String snapshotName,
    final String targetName, int filesExpected, Path copyDir, boolean overwrite) throws Exception {
    testExportFileSystemState(tableName, snapshotName, targetName, filesExpected, copyDir,
      overwrite, false);
  }

  protected void testExportFileSystemState(final TableName tableName, final String snapshotName,
    final String targetName, int filesExpected, Path copyDir, boolean overwrite, boolean resetTtl)
    throws Exception {
    testExportFileSystemState(TEST_UTIL.getConfiguration(), tableName, snapshotName, targetName,
      filesExpected, TEST_UTIL.getDefaultRootDirPath(), copyDir, overwrite, resetTtl,
      getBypassRegionPredicate(), true, false);
  }

  /**
   * Creates destination directory, runs ExportSnapshot() tool, and runs some verifications.
   */
  protected static void testExportFileSystemState(final Configuration conf,
    final TableName tableName, final String snapshotName, final String targetName,
    final int filesExpected, final Path srcDir, Path rawTgtDir, final boolean overwrite,
    final boolean resetTtl, final RegionPredicate bypassregionPredicate, final boolean success,
    final boolean checksumVerify) throws Exception {
    FileSystem tgtFs = rawTgtDir.getFileSystem(conf);
    FileSystem srcFs = srcDir.getFileSystem(conf);
    Path tgtDir = rawTgtDir.makeQualified(tgtFs.getUri(), tgtFs.getWorkingDirectory());

    // Export Snapshot
    int res = runExportSnapshot(conf, snapshotName, targetName, srcDir, rawTgtDir, overwrite,
      resetTtl, checksumVerify, true, true);
    assertEquals("success " + success + ", res=" + res, success ? 0 : 1, res);
    if (!success) {
      final Path targetDir = new Path(HConstants.SNAPSHOT_DIR_NAME, targetName);
      assertFalse(tgtDir.toString() + " " + targetDir.toString(),
        tgtFs.exists(new Path(tgtDir, targetDir)));
      return;
    }
    LOG.info("Exported snapshot");

    // Verify File-System state
    FileStatus[] rootFiles = tgtFs.listStatus(tgtDir);
    assertEquals(filesExpected > 0 ? 2 : 1, rootFiles.length);
    for (FileStatus fileStatus : rootFiles) {
      String name = fileStatus.getPath().getName();
      assertTrue(fileStatus.toString(), fileStatus.isDirectory());
      assertTrue(name.toString(), name.equals(HConstants.SNAPSHOT_DIR_NAME)
        || name.equals(HConstants.HFILE_ARCHIVE_DIRECTORY));
    }
    LOG.info("Verified filesystem state");

    // Compare the snapshot metadata and verify the hfiles
    final Path snapshotDir = new Path(HConstants.SNAPSHOT_DIR_NAME, snapshotName);
    final Path targetDir = new Path(HConstants.SNAPSHOT_DIR_NAME, targetName);
    verifySnapshotDir(srcFs, new Path(srcDir, snapshotDir), tgtFs, new Path(tgtDir, targetDir));
    Set<String> snapshotFiles =
      verifySnapshot(conf, tgtFs, tgtDir, tableName, targetName, resetTtl, bypassregionPredicate);
    assertEquals(filesExpected, snapshotFiles.size());
  }

  /*
   * verify if the snapshot folder on file-system 1 match the one on file-system 2
   */
  protected static void verifySnapshotDir(final FileSystem fs1, final Path root1,
    final FileSystem fs2, final Path root2) throws IOException {
    assertEquals(listFiles(fs1, root1, root1), listFiles(fs2, root2, root2));
  }

  /*
   * Verify if the files exists
   */
  protected static Set<String> verifySnapshot(final Configuration conf, final FileSystem fs,
    final Path rootDir, final TableName tableName, final String snapshotName,
    final boolean resetTtl, final RegionPredicate bypassregionPredicate) throws IOException {
    final Path exportedSnapshot =
      new Path(rootDir, new Path(HConstants.SNAPSHOT_DIR_NAME, snapshotName));
    final Set<String> snapshotFiles = new HashSet<>();
    final Path exportedArchive = new Path(rootDir, HConstants.HFILE_ARCHIVE_DIRECTORY);
    SnapshotReferenceUtil.visitReferencedFiles(conf, fs, exportedSnapshot,
      new SnapshotReferenceUtil.SnapshotVisitor() {
        @Override
        public void storeFile(final RegionInfo regionInfo, final String family,
          final SnapshotRegionManifest.StoreFile storeFile) throws IOException {
          if (bypassregionPredicate != null && bypassregionPredicate.evaluate(regionInfo)) {
            return;
          }

          if (!storeFile.hasReference() && !StoreFileInfo.isReference(storeFile.getName())) {
            String hfile = storeFile.getName();
            snapshotFiles.add(hfile);
            verifyNonEmptyFile(new Path(exportedArchive,
              new Path(CommonFSUtils.getTableDir(new Path("./"), tableName),
                new Path(regionInfo.getEncodedName(), new Path(family, hfile)))));
          } else {
            Pair<String, String> referredToRegionAndFile =
              StoreFileInfo.getReferredToRegionAndFile(storeFile.getName());
            String region = referredToRegionAndFile.getFirst();
            String hfile = referredToRegionAndFile.getSecond();
            snapshotFiles.add(hfile);
            verifyNonEmptyFile(new Path(exportedArchive,
              new Path(CommonFSUtils.getTableDir(new Path("./"), tableName),
                new Path(region, new Path(family, hfile)))));
          }
        }

        private void verifyNonEmptyFile(final Path path) throws IOException {
          assertTrue(path + " should exists", fs.exists(path));
          assertTrue(path + " should not be empty", fs.getFileStatus(path).getLen() > 0);
        }
      });

    // Verify Snapshot description
    SnapshotDescription desc = SnapshotDescriptionUtils.readSnapshotInfo(fs, exportedSnapshot);
    assertTrue(desc.getName().equals(snapshotName));
    assertTrue(desc.getTable().equals(tableName.getNameAsString()));
    if (resetTtl) {
      assertEquals(HConstants.DEFAULT_SNAPSHOT_TTL, desc.getTtl());
    }
    return snapshotFiles;
  }

  private static Set<String> listFiles(final FileSystem fs, final Path root, final Path dir)
    throws IOException {
    Set<String> files = new HashSet<>();
    LOG.debug("List files in {} in root {} at {}", fs, root, dir);
    int rootPrefix = root.makeQualified(fs.getUri(), fs.getWorkingDirectory()).toString().length();
    FileStatus[] list = CommonFSUtils.listStatus(fs, dir);
    if (list != null) {
      for (FileStatus fstat : list) {
        LOG.debug(Objects.toString(fstat.getPath()));
        if (fstat.isDirectory()) {
          files.addAll(listFiles(fs, root, fstat.getPath()));
        } else {
          files.add(fstat.getPath().makeQualified(fs).toString().substring(rootPrefix));
        }
      }
    }
    return files;
  }

  private Path getHdfsDestinationDir() {
    Path rootDir = TEST_UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getRootDir();
    Path path =
      new Path(new Path(rootDir, "export-test"), "export-" + EnvironmentEdgeManager.currentTime());
    LOG.info("HDFS export destination path: " + path);
    return path;
  }

  static Path getLocalDestinationDir(HBaseTestingUtil htu) {
    Path path = htu.getDataTestDir("local-export-" + EnvironmentEdgeManager.currentTime());
    try {
      FileSystem fs = FileSystem.getLocal(htu.getConfiguration());
      LOG.info("Local export destination path: " + path);
      return path.makeQualified(fs.getUri(), fs.getWorkingDirectory());
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  private static void removeExportDir(final Path path) throws IOException {
    FileSystem fs = FileSystem.get(path.toUri(), new Configuration());
    fs.delete(path, true);
  }

  private static int runExportSnapshot(final Configuration conf, final String sourceSnapshotName,
    final String targetSnapshotName, final Path srcDir, Path rawTgtDir, final boolean overwrite,
    final boolean resetTtl, final boolean checksumVerify, final boolean noSourceVerify,
    final boolean noTargetVerify) throws Exception {
    FileSystem tgtFs = rawTgtDir.getFileSystem(conf);
    FileSystem srcFs = srcDir.getFileSystem(conf);
    Path tgtDir = rawTgtDir.makeQualified(tgtFs.getUri(), tgtFs.getWorkingDirectory());
    LOG.info("tgtFsUri={}, tgtDir={}, rawTgtDir={}, srcFsUri={}, srcDir={}", tgtFs.getUri(), tgtDir,
      rawTgtDir, srcFs.getUri(), srcDir);
    List<String> opts = new ArrayList<>();
    opts.add("--snapshot");
    opts.add(sourceSnapshotName);
    opts.add("--copy-to");
    opts.add(tgtDir.toString());
    if (!targetSnapshotName.equals(sourceSnapshotName)) {
      opts.add("--target");
      opts.add(targetSnapshotName);
    }
    if (overwrite) {
      opts.add("--overwrite");
    }
    if (resetTtl) {
      opts.add("--reset-ttl");
    }
    if (!checksumVerify) {
      opts.add("--no-checksum-verify");
    }
    if (!noSourceVerify) {
      opts.add("--no-source-verify");
    }
    if (!noTargetVerify) {
      opts.add("--no-target-verify");
    }

    // Export Snapshot
    return run(conf, new ExportSnapshot(), opts.toArray(new String[opts.size()]));
  }
}
