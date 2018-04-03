package com.rsclouds.gtparallel.core.hadoop.mapreduce;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.util.Base64;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.StringUtils;

import com.google.protobuf.InvalidProtocolBufferException;

public class TableRegionSplitInputFormat extends
		TableRegionSplitInputFormatBase implements Configurable {

	private final Log LOG = LogFactory
			.getLog(TableRegionSplitInputFormat.class);

	/** Job parameter that specifies the input table. */
	public static final String INPUT_TABLE = "hbase.mapreduce.inputtable";
	/**
	 * Base-64 encoded scanner. All other SCAN_ confs are ignored if this is
	 * specified. See {@link TableMapReduceUtil#convertScanToString(Scan)} for
	 * more details.
	 */
	public static final String SCAN = "hbase.mapreduce.scan";
	/** Scan start row */
	public static final String SCAN_ROW_START = "hbase.mapreduce.scan.row.start";
	/** Scan stop row */
	public static final String SCAN_ROW_STOP = "hbase.mapreduce.scan.row.stop";
	/** Column Family to Scan */
	public static final String SCAN_COLUMN_FAMILY = "hbase.mapreduce.scan.column.family";
	/** Space delimited list of columns and column families to scan. */
	public static final String SCAN_COLUMNS = "hbase.mapreduce.scan.columns";
	/** The timestamp used to filter columns with a specific timestamp. */
	public static final String SCAN_TIMESTAMP = "hbase.mapreduce.scan.timestamp";
	/**
	 * The starting timestamp used to filter columns with a specific range of
	 * versions.
	 */
	public static final String SCAN_TIMERANGE_START = "hbase.mapreduce.scan.timerange.start";
	/**
	 * The ending timestamp used to filter columns with a specific range of
	 * versions.
	 */
	public static final String SCAN_TIMERANGE_END = "hbase.mapreduce.scan.timerange.end";
	/** The maximum number of version to return. */
	public static final String SCAN_MAXVERSIONS = "hbase.mapreduce.scan.maxversions";
	/** Set to false to disable server-side caching of blocks for this scan. */
	public static final String SCAN_CACHEBLOCKS = "hbase.mapreduce.scan.cacheblocks";
	/** The number of rows for caching that will be passed to scanners. */
	public static final String SCAN_CACHEDROWS = "hbase.mapreduce.scan.cachedrows";
	/** Set the maximum number of values to return for each call to next(). */
	public static final String SCAN_BATCHSIZE = "hbase.mapreduce.scan.batchsize";

	/** The configuration. */
	private Configuration conf = null;

	/**
	 * Returns the current configuration.
	 * 
	 * @return The current configuration.
	 * @see org.apache.hadoop.conf.Configurable#getConf()
	 */
	@Override
	public Configuration getConf() {
		return conf;
	}

	/**
	 * Sets the configuration. This is used to set the details for the table to
	 * be scanned.
	 * 
	 * @param configuration
	 *            The configuration to set.
	 * @see org.apache.hadoop.conf.Configurable#setConf(org.apache.hadoop.conf.Configuration)
	 */
	@Override
	public void setConf(Configuration configuration) {
		this.conf = configuration;
		String tableName = conf.get(INPUT_TABLE);
		try {
			setHTable(new HTable(new Configuration(conf), tableName));
		} catch (Exception e) {
			LOG.error(StringUtils.stringifyException(e));
		}

		Scan scan = null;

		if (conf.get(SCAN) != null) {
			try {
//				scan = TableMapReduceUtil.convertStringToScan(conf.get(SCAN));
				scan = convertStringToScan(conf.get(SCAN));
			} catch (IOException e) {
				LOG.error("An error occurred.", e);
			}
		} else {
			try {
				scan = new Scan();

				if (conf.get(SCAN_ROW_START) != null) {
					scan.setStartRow(Bytes.toBytes(conf.get(SCAN_ROW_START)));
				}

				if (conf.get(SCAN_ROW_STOP) != null) {
					scan.setStopRow(Bytes.toBytes(conf.get(SCAN_ROW_STOP)));
				}

				if (conf.get(SCAN_COLUMNS) != null) {
					addColumns(scan, conf.get(SCAN_COLUMNS));
				}

				if (conf.get(SCAN_COLUMN_FAMILY) != null) {
					scan.addFamily(Bytes.toBytes(conf.get(SCAN_COLUMN_FAMILY)));
				}

				if (conf.get(SCAN_TIMESTAMP) != null) {
					scan.setTimeStamp(Long.parseLong(conf.get(SCAN_TIMESTAMP)));
				}

				if (conf.get(SCAN_TIMERANGE_START) != null
						&& conf.get(SCAN_TIMERANGE_END) != null) {
					scan.setTimeRange(
							Long.parseLong(conf.get(SCAN_TIMERANGE_START)),
							Long.parseLong(conf.get(SCAN_TIMERANGE_END)));
				}

				if (conf.get(SCAN_MAXVERSIONS) != null) {
					scan.setMaxVersions(Integer.parseInt(conf
							.get(SCAN_MAXVERSIONS)));
				}

				if (conf.get(SCAN_CACHEDROWS) != null) {
					scan.setCaching(Integer.parseInt(conf.get(SCAN_CACHEDROWS)));
				}

				if (conf.get(SCAN_BATCHSIZE) != null) {
					scan.setBatch(Integer.parseInt(conf.get(SCAN_BATCHSIZE)));
				}

				// false by default, full table scans generate too much BC churn
				scan.setCacheBlocks((conf.getBoolean(SCAN_CACHEBLOCKS, false)));
			} catch (Exception e) {
				LOG.error(StringUtils.stringifyException(e));
			}
		}

		setScan(scan);
	}

	public static Scan convertStringToScan(String base64) throws IOException {
		byte[] decoded = Base64.decode(base64);
		ClientProtos.Scan scan;
		try {
			scan = ClientProtos.Scan.parseFrom(decoded);
		} catch (InvalidProtocolBufferException ipbe) {
			throw new IOException(ipbe);
		}

		return ProtobufUtil.toScan(scan);
	}

	/**
	 * Parses a combined family and qualifier and adds either both or just the
	 * family in case there is no qualifier. This assumes the older colon
	 * divided notation, e.g. "family:qualifier".
	 * 
	 * @param scan
	 *            The Scan to update.
	 * @param familyAndQualifier
	 *            family and qualifier
	 * @return A reference to this instance.
	 * @throws IllegalArgumentException
	 *             When familyAndQualifier is invalid.
	 */
	private static void addColumn(Scan scan, byte[] familyAndQualifier) {
		byte[][] fq = KeyValue.parseColumn(familyAndQualifier);
		if (fq.length == 1) {
			scan.addFamily(fq[0]);
		} else if (fq.length == 2) {
			scan.addColumn(fq[0], fq[1]);
		} else {
			throw new IllegalArgumentException(
					"Invalid familyAndQualifier provided.");
		}
	}

	/**
	 * Adds an array of columns specified using old format, family:qualifier.
	 * <p>
	 * Overrides previous calls to {@link Scan#addColumn(byte[], byte[])}for any
	 * families in the input.
	 * 
	 * @param scan
	 *            The Scan to update.
	 * @param columns
	 *            array of columns, formatted as <code>family:qualifier</code>
	 * @see Scan#addColumn(byte[], byte[])
	 */
	public static void addColumns(Scan scan, byte[][] columns) {
		for (byte[] column : columns) {
			addColumn(scan, column);
		}
	}

	/**
	 * Convenience method to parse a string representation of an array of column
	 * specifiers.
	 * 
	 * @param scan
	 *            The Scan to update.
	 * @param columns
	 *            The columns to parse.
	 */
	private static void addColumns(Scan scan, String columns) {
		String[] cols = columns.split(" ");
		for (String col : cols) {
			addColumn(scan, Bytes.toBytes(col));
		}
	}
}
