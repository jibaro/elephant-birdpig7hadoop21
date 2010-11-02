package com.twitter.elephantbird.pig.load;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.pig.FuncSpec;
import org.apache.pig.backend.datastorage.DataStorage;
import org.apache.pig.backend.datastorage.SeekableInputStream;
import org.apache.pig.backend.datastorage.SeekableInputStream.FLAGS;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.elephantbird.pig.util.LzoBufferedPositionedInputStream;

/**
 * This class implements the Pig Slice interface, meaning it both represents an input split
 * and begins processing on a mapper by binding a loader to each input split.
 */
public class LzoSlice{
  private static final Logger LOG = LoggerFactory.getLogger(LzoSlice.class);

  // Generated by Eclipse.
  private static final long serialVersionUID = 4921280606900935966L;

  private final String filename_;
  private long start_;
  private long length_;
  private CompressionInputStream is_;
  private SeekableInputStream fsis_;
  private LzoBaseLoadFunc loader_;
  private FuncSpec loadFuncSpec_;

  /**
   * Construct a slice with the given specifications.
   * @param filename the filename from which the block comes.
   * @param start the byte offset of the start of the block.  If this is zero, it's necessary to read the
   *        file header before real data reading starts.  If this is nonzero, it's assumed to be at the beginning
   *        of an LZO block boundary, otherwise decompression will fail.
   * @param length the length of the block.
   * @param loadFuncSpec the spec for invoking the loader function.
   */
  public LzoSlice(String filename, long start, long length, FuncSpec loadFuncSpec) {
    LOG.debug("LzoSlice::LzoSlice, file = " + filename + ", start = " + start + ", length = " + length);
    filename_ = filename;
    start_ = start;
    length_ = length;
    loadFuncSpec_ = loadFuncSpec;
  }

  public void close() throws IOException {
    if (is_ != null) {
      is_.close();
    }
  }

  public long getStart() {
    return start_;
  }

  public long getLength() {
    return length_;
  }

  /*
   * return the filename being operated on
   */
  public String getFilename() {
    return filename_;
  }

  /**
   * Return the set of servers which contain any blocks of the given file.
   */
  public String[] getLocations() {
    try {
      FileSystem fs = FileSystem.get(new Configuration());
      Set<String> locations = new HashSet<String>();

      FileStatus status = fs.getFileStatus(new Path(filename_));
      BlockLocation[] b = fs.getFileBlockLocations(status, getStart(), getLength());
      for (int i = 0; i < b.length; i++) {
        locations.addAll(Arrays.asList(b[i].getHosts()));
      }
      return locations.toArray(new String[locations.size()]);
    } catch (IOException e) {
      LOG.error("Caught exception: " + e);
      return null;
    }
  }

  public long getPos() throws IOException {
    return fsis_.tell();
  }

  public float getProgress() throws IOException {
    float progress = getPos() - start_;
    float finish = getLength();
    return progress / finish;
  }

  /**
   * Set up the slice by creating the compressed input stream around the given Hadoop file input stream.
   * @param store the Pig storage object.
   */
  public void init(DataStorage store) throws IOException {
    fsis_ = store.asElement(store.getActiveContainer(), filename_).sopen();

    CompressionCodecFactory compressionCodecs = new CompressionCodecFactory(new Configuration());
    final CompressionCodec codec = compressionCodecs.getCodec(new Path(filename_));
    is_ = codec.createInputStream(fsis_, codec.createDecompressor());
    // At this point, is_ will already be a nonzero number of bytes into the file, because
    // the Lzop codec reads the header upon opening the stream.
    boolean beginsAtHeader = false;
    if (start_ != 0) {
      // If start_ is nonzero, seek there to begin reading, using SEEK_SET per above.
      fsis_.seek(start_, FLAGS.SEEK_SET);
    } else {
      // If start_ is zero, then it's actually at the header offset. Adjust based on this.
      start_ = fsis_.tell();
      length_ -= start_;
      beginsAtHeader = true;
    }

    LOG.info("Creating constructor for class " + loadFuncSpec_);
    // Use instantiateFuncFromSpec to maintain the arguments passed in from the Pig script.
    loader_ = (LzoBaseLoadFunc) PigContext.instantiateFuncFromSpec(loadFuncSpec_);
    loader_.setBeginsAtHeader(beginsAtHeader);
    // Wrap Pig's BufferedPositionedInputStream with our own, which gives positions based on the number
    // of compressed bytes read rather than the number of uncompressed bytes read.
    loader_.bindTo(filename_, new LzoBufferedPositionedInputStream(is_, start_), start_, start_ + length_);
  }

  /**
   * Get the next tuple by delegating to the loader.
   * @param tuple the tuple to be filled out
   * @return true if the load should continue, i.e. if the tuple was filled out this round.
   */
  public boolean next(Tuple tuple) throws IOException {
    // Delegate to the loader.
    Tuple t = loader_.getNext();
    if (t == null) {
      return false;
    }
    tuple.reference(t);
    return true;
  }


}
