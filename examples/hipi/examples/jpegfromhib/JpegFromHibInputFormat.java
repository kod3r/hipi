package hipi.examples.jpegfromhib;

import hipi.imagebundle.AbstractImageBundle;
import hipi.imagebundle.HipiImageBundle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

public class JpegFromHibInputFormat extends FileInputFormat<NullWritable, BytesWritable> 
{

	/**
	 * Returns an object that can be used to read records of type ImageInputFormat
	 */
	@Override
	public RecordReader<NullWritable, BytesWritable> createRecordReader(InputSplit genericSplit, TaskAttemptContext context) 
	throws IOException, InterruptedException {
		return new JpegFromHibRecordReader();
	}

	@Override
	public List<InputSplit> getSplits(JobContext job) throws IOException {
		Configuration conf = job.getConfiguration();
		int numMapTasks = conf.getInt("hipi.map.tasks", 0);
		List<InputSplit> splits = new ArrayList<InputSplit>();
		for (FileStatus file : listStatus(job)) {
			Path path = file.getPath();
			FileSystem fs = path.getFileSystem(conf);
			HipiImageBundle hib = new HipiImageBundle(path, conf);
			hib.open(AbstractImageBundle.FILE_MODE_READ);
			// offset should be guaranteed to be in order
			List<Long> offsets = hib.getOffsets();
			BlockLocation[] blkLocations = fs.getFileBlockLocations(hib.getDataFile(), 0, offsets.get(offsets.size() - 1));
			if (numMapTasks == 0) {
				int i = 0, b = 0;
				long lastOffset = 0, currentOffset = 0;
				for (; (b < blkLocations.length) && (i < offsets.size()); b++) {
					long next = blkLocations[b].getOffset() + blkLocations[b].getLength();
					while (currentOffset < next && i < offsets.size()) {
						currentOffset = offsets.get(i);
						i++;
					}
					String[] hosts = null;
					if (currentOffset > next) {
						Set<String> hostSet = new HashSet<String>();
						int endIndex = getBlockIndex(blkLocations, currentOffset - 1);
						for (int j = b; j < endIndex; j++) {
							String[] blkHosts = blkLocations[j].getHosts();
							for (int k = 0; k < blkHosts.length; k++)
								hostSet.add(blkHosts[k]);
						}
						hosts = (String[]) hostSet.toArray(new String[hostSet.size()]);
					} else { // currentOffset == next
						hosts = blkLocations[b].getHosts();
					}
					splits.add(new FileSplit(hib.getDataFile().getPath(), lastOffset, currentOffset - lastOffset, hosts));
					lastOffset = currentOffset;
				}
				System.out.println(b + " tasks spawned");
			} else {
				int imageRemaining = offsets.size();
				int i = 0, taskRemaining = numMapTasks;
				long lastOffset = 0, currentOffset;
				while (imageRemaining > 0) {
					int numImages = imageRemaining / taskRemaining;
					if (imageRemaining % taskRemaining > 0)
						numImages++;
					int next = Math.min(offsets.size() - i, numImages) - 1;
					int startIndex = getBlockIndex(blkLocations, lastOffset);
					currentOffset = offsets.get(i + next);
					int endIndex = getBlockIndex(blkLocations, currentOffset - 1);
					ArrayList<String> hosts = new ArrayList<String>();
					// check getBlockIndex, and getBlockSize
					for (int j = startIndex; j <= endIndex; j++) {
						String[] blkHosts = blkLocations[j].getHosts();
						for (int k = 0; k < blkHosts.length; k++)
							hosts.add(blkHosts[k]);
					}
					splits.add(new FileSplit(hib.getDataFile().getPath(), lastOffset, currentOffset - lastOffset, hosts.toArray(new String[hosts.size()])));
					lastOffset = currentOffset;
					i += next + 1;
					taskRemaining--;
					imageRemaining -= numImages;
					System.out.println("imageRemaining: " + imageRemaining + "\ttaskRemaining: " + taskRemaining + "\tlastOffset: " + lastOffset + "\ti: " + i);
				}
			}
			hib.close();
		}
		return splits;
	}
}
