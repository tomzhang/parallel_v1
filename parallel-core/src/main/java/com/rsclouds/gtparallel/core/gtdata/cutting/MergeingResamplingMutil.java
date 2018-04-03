package com.rsclouds.gtparallel.core.gtdata.cutting;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat;
import org.apache.hadoop.hbase.mapreduce.TableReducer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.rsclouds.gtparallel.core.common.CoreConfig;
import com.rsclouds.gtparallel.core.hadoop.io.CoordinateValue;
import com.rsclouds.gtparallel.core.hadoop.io.ScaleRowColText;
import com.rsclouds.gtparallel.gtdata.service.HbaseBase;
import com.rsclouds.gtparallel.gtdata.utills.GtDataConfig;
import com.rsclouds.gtparallel.gtdata.utills.MD5Calculate;



public class MergeingResamplingMutil extends Configured implements Tool{
	private static final Log LOG = LogFactory
			.getLog(MergeingResamplingMutil.class);

	/**
	 * 
	 * @author xiaoshaolin
	 * 
	 *         read the data from meta table and cacluate which pictures will be
	 *         to merge a new picture
	 */
	static class ReadHbaseMapper extends
			TableMapper<ScaleRowColText, CoordinateValue> {

		private ScaleRowColText rowcol = new ScaleRowColText();
		private CoordinateValue coorValue = new CoordinateValue();

		protected void setup(Context context) throws IOException,
				InterruptedException {
			String jid = context.getJobID().toString();
			String jobid = context.getConfiguration().get(CoreConfig.JOBID);

			Map<String, String> map = new HashMap<String, String>();
			map.put(CoreConfig.JOB.JID.strVal, jid);
			HbaseBase.writeRows(CoreConfig.MANAGER_JOB_TABLE, jobid,
					CoreConfig.JOB.FAMILY.strVal, map);
			LOG.info("writeRows jid: " + jid + " into " + jobid);
		}

		public void map(ImmutableBytesWritable key, Result values,
				Context context) throws IOException, InterruptedException {
			String rowkey = new String(key.get());
			if (rowkey.endsWith(".png")) {
				String[] splits = rowkey.split("/");
				int length = splits.length;
				long rowLong = Long
						.valueOf(splits[length - 3].substring(1), 16);
				String str = splits[length - 1];
				int indexof = str.lastIndexOf('.');
				long colLong = Long.valueOf(str.substring(1, indexof), 16);

				rowcol.setScale(2);
				rowcol.setRow(rowLong / 2);
				rowcol.setCol(colLong / 2);
				coorValue.setRow_coor((int)(rowLong % 2));
				coorValue.setCol_coor((int)(colLong % 2));
				String strM = new String(values.getValue(GtDataConfig.META.FAMILY.byteVal, 
						GtDataConfig.META.URL.byteVal));
				if (strM.length() == 0)
					System.out.println("url empty");
				coorValue.setMD5(values.getValue(GtDataConfig.META.FAMILY.byteVal, GtDataConfig.META.URL.byteVal));
				context.write(rowcol, coorValue);
				rowcol.setScale(4);
				rowcol.setRow(rowLong / 4);
				rowcol.setCol(colLong / 4);
				coorValue.setRow_coor((int)(rowLong % 4));
				coorValue.setCol_coor((int)(colLong % 4));
//				coorValue.setMD5(values.getValue(
//						GtDataConfig.META.FAMILY.byteVal,
//						GtDataConfig.META.URL.byteVal));
				context.write(rowcol, coorValue);
			}
		}
	}

	/**
	 * 
	 * @author xiaoshaolin
	 * 
	 *         merge the pictures to a new picture and then resample
	 */
	static class ResampleReducer extends
			TableReducer<ScaleRowColText, CoordinateValue, NullWritable> {
		private int scale; // the scaling of picture
		private HTable resourceTable;
		private StringBuilder rowkey;
		private String curentLayerName;
		private String nextLayerName; 
		private StringBuilder rowName;
		private StringBuilder colName;
		private int rowkeyPrefixLen;
		private StringBuilder MD5;
		private int layers; 
		private int count2 = 0;
		private int count4 = 0;
		ByteArrayOutputStream byteArrayOut = new ByteArrayOutputStream();

		protected void setup(Context context) throws IOException,
				InterruptedException {
			super.setup(context);
			Configuration conf = context.getConfiguration();
			resourceTable = new HTable(conf, CoreConfig.MAP_RES_TABLE);
			resourceTable.setAutoFlush(false, true);
			rowkey = new StringBuilder(conf.get(CoreConfig.CUTTING_OUTPUTPATH));
			rowkeyPrefixLen = rowkey.length();
			rowName = new StringBuilder();
			colName = new StringBuilder();
			MD5 = new StringBuilder();
			layers = conf.getInt(CoreConfig.KEY_CURRENT_LAYER, 0); 
			
			curentLayerName = Integer.toHexString(layers);
			if (layers < 16) {
				curentLayerName = "0" + curentLayerName;
			}
			layers --;
			nextLayerName = Integer.toHexString(layers);
			if (layers < 16) {
				nextLayerName = "0" + nextLayerName;
			}
		}

		public void reduce(ScaleRowColText key, Iterable<CoordinateValue> values,
				Context context) throws IOException, InterruptedException {
			int width_all = 0;
			int height_all = 0;
			int length;
			int i = 0;
			List<CoordinateValue> coorList = new ArrayList<CoordinateValue>();
			List<ImageIcon> imageList = new ArrayList<ImageIcon>();

			for (CoordinateValue val : values) {
				Get get = new Get(val.getMD5().copyBytes());
				get.addColumn(Bytes.toBytes("img"), Bytes.toBytes("data"));
				Result result = resourceTable.get(get);
				CoordinateValue tmp = new CoordinateValue();

				byte[] bytes = result.getValue(
						GtDataConfig.RESOURCE.FAMILY.byteVal,
						GtDataConfig.RESOURCE.DATA.byteVal);
				tmp.setCol_coor(val.getCol_coor());
				tmp.setRow_coor(val.getRow_coor());
				tmp.setMD5(bytes);
				coorList.add(tmp);
				imageList.add(new ImageIcon(bytes));
				i++;
			}
			length = coorList.size();
			scale = key.getScale();

			width_all = CoreConfig.WIDTH_DEFAULT * scale;
			height_all = CoreConfig.HEIGHT_DEFAULT * scale;

			// merge the picture
			BufferedImage img = new BufferedImage(width_all, height_all,
					BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D gs = (Graphics2D) img.getGraphics();
			for (i = 0; i < length; i++) {
				CoordinateValue val = coorList.get(i);
				int col_coor = val.getCol_coor();
				int row_coor = val.getRow_coor();
				gs.drawImage(imageList.get(i).getImage(), CoreConfig.HEIGHT_DEFAULT * col_coor, CoreConfig.HEIGHT_DEFAULT * row_coor, imageList.get(i).getImageObserver());
			}
			gs.dispose();

			// resample the picture
			width_all = width_all / scale;
			height_all = height_all / scale;
			Image reImage = img.getScaledInstance(width_all, height_all,
					Image.SCALE_AREA_AVERAGING);
			BufferedImage tag = new BufferedImage(CoreConfig.WIDTH_DEFAULT,
					CoreConfig.HEIGHT_DEFAULT, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics g = tag.getGraphics();
			g.drawImage(reImage, 0, 0, null);
			g.dispose();

			// output the file
			ImageIO.write(tag, "png", byteArrayOut);
			byte[] data = byteArrayOut.toByteArray();
			MD5.append(MD5Calculate.fileByteMD5(data));

			rowName.replace(0, rowName.length(), Long.toHexString(key.getRow()));
			int count = 8 - rowName.length();
			for (i = 0; i < count; i++) {
				rowName.insert(0, "0");
			}
			rowName.insert(0, "R");
			colName.replace(0, rowName.length(), Long.toHexString(key.getCol()));
			count = 8 - colName.length();
			for (i = 0; i < count; i++) {
				colName.insert(0, "0");
			}
			colName.insert(0, "C");
			if(scale == 2) {
				count2 ++;
				rowkey.replace(rowkeyPrefixLen-2, rowkeyPrefixLen, curentLayerName);
			}else {
				count4++;
				rowkey.replace(rowkeyPrefixLen-2, rowkeyPrefixLen, nextLayerName);
			}
			rowkey.replace(rowkeyPrefixLen, rowkey.length(),
					"//" + rowName.toString());
			
			Put put = new Put(Bytes.toBytes(rowkey.toString()));
			//meta表插入行号目录
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.DFS.byteVal, Bytes.toBytes("0"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.SIZE.byteVal, Bytes.toBytes("-1"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.URL.byteVal, Bytes.toBytes(""));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.TIME.byteVal,
					Bytes.toBytes("" + System.currentTimeMillis()));
			context.write(NullWritable.get(), put);

			//meta插入列号文件
			rowkey.replace(rowkeyPrefixLen, rowkey.length(), "/" + rowName.toString());
			put = new Put(Bytes.toBytes(rowkey.toString() + "//"
					+ colName.toString() + ".png"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.DFS.byteVal, Bytes.toBytes("0"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.SIZE.byteVal,
					Bytes.toBytes(String.valueOf(data.length)));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.URL.byteVal,
					Bytes.toBytes(MD5.toString()));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.TIME.byteVal,
					Bytes.toBytes("" + System.currentTimeMillis()));
			context.write(NullWritable.get(), put);

			//res表插入文件值
			put = new Put(Bytes.toBytes(MD5.toString()));
			put.add(GtDataConfig.RESOURCE.FAMILY.byteVal,
					GtDataConfig.RESOURCE.LINKS.byteVal, Bytes.toBytes("1"));
			put.add(GtDataConfig.RESOURCE.FAMILY.byteVal,
					GtDataConfig.RESOURCE.DATA.byteVal, data);
			resourceTable.put(put);

			byteArrayOut.reset();
			MD5.delete(0, MD5.length());
			coorList.clear();
		}

		public void cleanup(Context context) throws IOException,
				InterruptedException {
			super.cleanup(context);
			resourceTable.flushCommits();
			resourceTable.close();
			Configuration conf = context.getConfiguration();
			
			HTable table = new HTable(conf, CoreConfig.MAP_META_TABLE);
			StringBuilder rowkeyStrBuilder = new StringBuilder(
					conf.get(CoreConfig.CUTTING_OUTPUTPATH));
			rowkeyStrBuilder.insert(rowkeyStrBuilder.lastIndexOf("/L"), "/");
			Put put = new Put(Bytes.toBytes(rowkeyStrBuilder.toString()));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.DFS.byteVal, Bytes.toBytes("0"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.SIZE.byteVal, Bytes.toBytes("-1"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.URL.byteVal, Bytes.toBytes(""));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.TIME.byteVal,
					Bytes.toBytes("" + System.currentTimeMillis()));
			table.put(put);

			rowkeyStrBuilder.replace(rowkeyStrBuilder.length() -2, rowkeyStrBuilder.length(), nextLayerName);
			put = new Put(Bytes.toBytes(rowkeyStrBuilder.toString()));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.DFS.byteVal, Bytes.toBytes("0"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.SIZE.byteVal, Bytes.toBytes("-1"));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.URL.byteVal, Bytes.toBytes(""));
			put.add(GtDataConfig.META.FAMILY.byteVal,
					GtDataConfig.META.TIME.byteVal,
					Bytes.toBytes("" + System.currentTimeMillis()));
			table.put(put);
			table.close();
			System.out.println("count2 = " + count2);
			System.out.println("count4 = " + count4);
		}
	}

	public int run(String[] args) throws Exception {
		LOG.info("Merging agrs : " + ArrayUtils.toString(args));
		Configuration conf = HBaseConfiguration.create();
		int layerNumber = Integer.valueOf(
				args[1].substring(args[1].length() - 2), 16);
		layerNumber--;
		conf.setInt(CoreConfig.KEY_CURRENT_LAYER, layerNumber);
		String layerName = Integer.toHexString(layerNumber);
		if (layerNumber < 16) {
			layerName = args[1].substring(0, args[1].length() - 2) + "0"
					+ layerName;
		} else {
			layerName = args[1].substring(0, args[1].length() - 2) + layerName;
		}
		LOG.info("MergingResamplingHbase output layers is: " + layerName);

		conf.set(TableInputFormat.INPUT_TABLE, CoreConfig.MAP_META_TABLE);
		conf.set(TableInputFormat.SCAN_ROW_START, args[1] + "/R0000000/");
		conf.set(TableInputFormat.SCAN_ROW_STOP, args[1] + "/Rffffffff/");
		conf.set(TableOutputFormat.OUTPUT_TABLE, CoreConfig.MAP_META_TABLE);

//		conf.set("hbase.nameserver.address", "7.2.168.192.in-addr.arpa");
		conf.set(CoreConfig.CUTTING_OUTPUTPATH, layerName);
		conf.set(CoreConfig.JOBID, args[0]);
		Job job = Job.getInstance(conf, "MergeingResamplingMutil");
		job.setJarByClass(MergeingResamplingMutil.class);
		job.setNumReduceTasks(6);
		layerNumber = Integer.parseInt(args[2]) - layerNumber;
		job.setJobName(layerNumber + "/" + args[2]);

		job.setOutputFormatClass(TableOutputFormat.class);
		job.setInputFormatClass(TableInputFormat.class);
		job.setMapperClass(ReadHbaseMapper.class);
		job.setReducerClass(ResampleReducer.class);
		job.setMapOutputKeyClass(ScaleRowColText.class);
		job.setMapOutputValueClass(CoordinateValue.class);

//		Scan scan = new Scan();
//		scan.addFamily(GtDataConfig.META.FAMILY.byteVal);
//		Filter filter = new RowFilter(CompareOp.EQUAL,
//				new BinaryPrefixComparator(args[1].getBytes()));
//		scan.setFilter(filter);
//		scan.setBatch(400);
//		scan.setCacheBlocks(false);
//		scan.setStartRow(Bytes.toBytes(args[1] + "/R0000000/"));
//		scan.setStopRow(Bytes.toBytes(args[1] + "/Rfffffff/"));
//
//		TableMapReduceUtil.initTableMapperJob(CoreConfig.MAP_META_TABLE, scan,
//				ReadHbaseMapper.class, ScaleRowColText.class, CoordinateValue.class,
//				job, true);
//		TableMapReduceUtil.initTableReducerJob(CoreConfig.MAP_META_TABLE,
//				ResampleReducer.class, job);
		return job.waitForCompletion(true) ? 0 : 1;
	}

	public static void main(String[] args) throws Exception {
		args = new String[3];
		args[0] = "/2015051403/Layers/_alllayers/L0f";
		args[1] = "/2015051403/Layers/_alllayers/L0f";
		args[2] = "16";
		int status = ToolRunner.run(new MergeingResamplingMutil(), args);
		System.exit(status);
	}
}
