package qunar.tc.qmq.backup.store.impl;

import com.google.common.base.Strings;
import org.hbase.async.*;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HBaseStore extends AbstractHBaseStore {
    private static final Logger LOG = LoggerFactory.getLogger(HBaseStore.class);

    private volatile boolean isClosed = false;
    private final HBaseClient client;

    public HBaseStore(byte[] table, byte[] family, byte[][] qualifiers, HBaseClient client) {
        super(table, family, qualifiers);
        this.client = client;
    }

    @Override
    protected void doBatchSave(byte[] table, byte[][] keys, byte[] family, byte[][] qualifiers, byte[][][] values) {
        for (int i = 0; i < keys.length; ++i) {
            doSave(table, keys[i], family, qualifiers, values[i]);
        }
    }

    private void doSave(byte[] table, byte[] key, byte[] family, byte[][] qualifiers, byte[][] value) {
        PutRequest request = new PutRequest(table, key, family, qualifiers, value);
        client.put(request).addBoth(input -> {
            if (input instanceof Throwable) {
                LOG.error("put backup message failed.", input);
            }
            return null;
        });
    }

    @Override
    protected <T, V> List<T> scan(byte[] table, String keyRegexp, String startKey, String stopKey, int maxNumRows, int maxVersions, byte[] family, byte[][] qualifiers, RowExtractor<T> rowExtractor) throws Exception {
        Scanner scanner = null;
        try {
            LOG.info("***************[scan] table:{},qualifiers:{},keyRegexp: {}, startKey: {}, stopKey: {}, maxNumRows: {}", new String(table, CharsetUtil.UTF_8), Arrays.toString(getStringArrays(qualifiers)),
                    keyRegexp, startKey, stopKey, maxNumRows);
            scanner = client.newScanner(table);
            if (!Strings.isNullOrEmpty(keyRegexp)) {
                scanner.setKeyRegexp(keyRegexp, CharsetUtil.UTF_8);
            }
            if (!Strings.isNullOrEmpty(startKey)) {
                scanner.setStartKey(startKey);
            }
            if (!Strings.isNullOrEmpty(stopKey)) {
                scanner.setStopKey(stopKey);
            }
            if (maxNumRows > 0) {
                scanner.setMaxNumRows(maxNumRows);
            }
            if (maxVersions > 0) {
                scanner.setMaxVersions(maxVersions);
            }
            if (family != null) {
                scanner.setFamily(family);
            }
            if (qualifiers != null && qualifiers.length > 0) {
                scanner.setQualifiers(qualifiers);
            }

            ArrayList<ArrayList<KeyValue>> rows = scanner.nextRows().join();
            if (rows != null && !rows.isEmpty()) {
                List<T> result = new ArrayList<T>();
                for (ArrayList<KeyValue> row : rows) {
                    T e = rowExtractor.extractData(row);
                    if (e == null) continue;
                    result.add(e);
                }
                return result;
            }
            return Collections.emptyList();
        } finally {
            if (scanner != null) {
                scanner.close();
            }

        }
    }

    @Override
    protected <T> T get(byte[] table, byte[] key, byte[] family, byte[][] qualifiers, RowExtractor<T> rowExtractor) throws Exception {
        LOG.info("****************[get] table:{},key:{},family:{},qualifiers:{}", new String(table, CharsetUtil.UTF_8), new String(key, CharsetUtil.UTF_8), new String(family, CharsetUtil.UTF_8),
                Arrays.toString(getStringArrays(qualifiers)));
        GetRequest request = new GetRequest(table, key).family(family).qualifiers(qualifiers);
        ArrayList<KeyValue> row = client.get(request).join();
        return (row == null || row.isEmpty()) ? null : rowExtractor.extractData(row);
    }

    private static String[] getStringArrays(final byte[][] bs) {
        if (bs != null) {
            String[] arr = new String[bs.length];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = new String(bs[i], CharsetUtil.UTF_8);
            }
            return arr;
        }
        return null;
    }

    @Override
    public void close() {
        if (isClosed) return;
        client.shutdown();
        isClosed = true;
    }
}
