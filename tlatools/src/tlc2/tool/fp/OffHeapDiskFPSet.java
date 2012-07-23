// Copyright (c) 2012 Microsoft Corporation.  All rights reserved.
package tlc2.tool.fp;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;

import sun.misc.Unsafe;
import tlc2.output.EC;
import util.Assert;

@SuppressWarnings({ "serial", "restriction" })
public class OffHeapDiskFPSet extends MSBDiskFPSet implements FPSetStatistic {
	
	private static sun.misc.Unsafe getUnsafe() {
		try {
			Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (sun.misc.Unsafe) f.get(null);
		} catch (Exception e) {
			//TODO handle if non-sun vm
			return null;
		}
	}
	
	/**
	 * 
	 */
	private final Unsafe u;
	
	/**
	 * 
	 */
	private final long baseAddress;
	
	/**
	 * 
	 */
	private final long maxInMemoryCapacity;

	/**
	 * A bucket containing collision elements
	 */
	private SortedSet<Long> collisionBucket; //TODO replace SortedSet with cheaper/faster long[]?!

	private final long addressSize;

	protected OffHeapDiskFPSet(long maxInMemoryCapacity) throws RemoteException {
		this(maxInMemoryCapacity, 0);
	}
	
	protected OffHeapDiskFPSet(final long maxInMemoryCapacity, int preBits) throws RemoteException {
		super(maxInMemoryCapacity);
		this.maxInMemoryCapacity = maxInMemoryCapacity;

		// Hack to free the buffer allocated by the super class!
		// We cheat in that we are no real subclass of DiskFPSet, but
		// refactoring seemed abstract base class seemed too much work at the
		// time.
		super.tbl = null;
		System.gc();

		//TODO impl preBits
		Assert.check(preBits == 0, EC.GENERAL); // not yet implemented!!!
		
		u = getUnsafe();
		
		// Determine base address which varies depending on machine architecture.
		addressSize = u.addressSize();
		
		// Allocate non-heap memory for maxInMemoryCapacity fingerprints
		baseAddress = u.allocateMemory(maxInMemoryCapacity * addressSize);
		
		// Null memory (could be done in parallel on segments when bottleneck).
		// This is essential as allocateMemory returns uninitialized memory and
		// memInsert/memLockup utilize 0L as a mark for an unused fingerprint slot.
		// Otherwise memory garbage wouldn't be distinguishable from a true fp. 
		for (long i = 0; i < maxInMemoryCapacity; i++) {
			u.putAddress(log2phy(i), 0L);
		}

		this.collisionBucket = new TreeSet<Long>();
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#sizeof()
	 */
	public long sizeof() {
		synchronized (this.rwLock) {
			long size = 44; // approx size of this DiskFPSet object
			size += maxInMemoryCapacity * (long) LongSize;
			size += getIndexCapacity() * 4;
			size += collisionBucket.size() * (long) LongSize; // ignoring the internal TreeSet overhead here
			return size;
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#needsDiskFlush()
	 */
	protected boolean needsDiskFlush() {
		return this.tblCnt >= this.maxTblCnt || sizeOfCollisionBucketExceeds(.05d);
	}

	/**
	 * @param limit A limit the collsionBucket is not allowed to exceed
	 * @return The proportional size of the collision bucket compared to the
	 *         size of the set.
	 */
	private boolean sizeOfCollisionBucketExceeds(final double limit) {
		// the fraction of collisionBucket size compared to the tbl size 
		final double dSize = (double) collisionBucket.size();
		final double dTblcnt = (double) tblCnt;
		return dSize / dTblcnt >= limit;
	}

	/**
	 * @param fp
	 * @return The logical position in the {@link ByteBuffer} for the given fingerprint.
	 */
	protected long getLogicalPosition(final long fp) {
		long position = ((int) (fp >>> 32 & this.mask) >> moveBy) * (long) InitialBucketCapacity;
		//Assert.check(0 <= position && position < maxInMemoryCapacity * addressSize, EC.GENERAL);
		return position;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#memLookup(long)
	 */
	boolean memLookup(long fp) {
		final long position = getLogicalPosition(fp);
		
		// Linearly search the logical bucket; 0L is an invalid fp and marks the
		// end of the allocated bucket
		long l = -1L;
		for (int i = 0; i < InitialBucketCapacity && l != 0L; i++) {
			l = u.getAddress(log2phy(position, i));
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			}
		}
		return collisionBucket.contains(fp);
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#memInsert(long)
	 */
	boolean memInsert(long fp) {
		final long position = getLogicalPosition(fp);

		long l = -1;
		long freePosition = -1L;
		for (int i = 0; i < InitialBucketCapacity && l != 0L; i++) {
			l = u.getAddress(log2phy(position, i));
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			} else if (l == 0L && freePosition == -1) {
				// empty or disk written slot found, simply insert at _current_ position
				u.putAddress(log2phy(position, i), fp);
				this.tblCnt++;
				return false;
			} else if (l < 0L && freePosition == -1) {
				// record free (disk written fp) slot
				freePosition = log2phy(position, i);
			}
		}
		
		// index slot overflow, thus add to collisionBucket
		if (!collisionBucket.contains(fp)) {
			if (freePosition > -1) {
				u.putAddress(freePosition, fp);
				this.tblCnt++;
				return false;
			} else {
				collisionBucket.add(fp);
				this.tblCnt++;
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Converts from logical bucket index numbers and in-bucket position to a
	 * physical memory address.
	 * 
	 * @param bucketNumber
	 * @param inBucketPosition
	 * @return The physical address of the fp slot
	 */
	private long log2phy(long bucketNumber, long inBucketPosition) {
		return log2phy(bucketNumber + inBucketPosition);
	}
	
	/**
	 * Converts from logical addresses to 
	 * physical memory addresses.
	 * 
	 * @param logicalAddress
	 * @return The physical address of the fp slot
	 */
	private long log2phy(long logicalAddress) {
		return baseAddress + (logicalAddress * addressSize);
	}

	/**
	 * Flush the contents of in-memory "this.tbl" to the backing disk file, and update
	 * "this.index". This method requires that "this.rwLock" has been acquired
	 * for writing by the caller, and that the mutex "this.rwLock" is also held.
	 */
	void flushTable() throws IOException {
		if (this.tblCnt == 0)
			return;
		
		if (sizeOfCollisionBucketExceeds(.05d)) {
			System.out.println("...due to collisionBucket size limit");
		}
		
		// merge array with disk file
		try {
			this.mergeNewEntries();
		} catch (IOException e) {
			String msg = "Error: merging entries into file "
					+ this.fpFilename + "  " + e;
			throw new IOException(msg);
		}
		this.tblCnt = 0;
		this.bucketsCapacity = 0;
		this.tblLoad = 0;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.MSBDiskFPSet#mergeNewEntries(java.io.RandomAccessFile, java.io.RandomAccessFile)
	 */
	protected void mergeNewEntries(RandomAccessFile inRAF, RandomAccessFile outRAF) throws IOException {
		final long buffLen = this.tblCnt;
		ByteBufferIterator itr = new ByteBufferIterator(this.u, this.baseAddress, collisionBucket, buffLen);
		
		// Precompute the maximum value of the new file
		long maxVal = itr.getLast();
		if (this.index != null) {
			maxVal = Math.max(maxVal, this.index[this.index.length - 1]);
		}

		int indexLen = calculateIndexLen(this.tblCnt);
		this.index = new long[indexLen];
		this.index[indexLen - 1] = maxVal;
		this.currIndex = 0;
		this.counter = 0;

		// initialize positions in "buff" and "inRAF"
		long value = 0L; // initialize only to make compiler happy
		boolean eof = false;
		if (this.fileCnt > 0) {
			try {
				value = inRAF.readLong();
			} catch (EOFException e) {
				eof = true;
			}
		} else {
			eof = true;
		}

		// merge while both lists still have elements remaining
		long fp = itr.next();
		while (!eof) {
			if (value < fp || !itr.hasNext()) { // check for itr.hasNext() here to write last value when itr is used up.
				this.writeFP(outRAF, value);
				try {
					value = inRAF.readLong();
				} catch (EOFException e) {
					eof = true;
				}
			} else {
				// prevent converting every long to String when assertion holds (this is expensive)
				if (value == fp) {
					Assert.check(false, EC.TLC_FP_VALUE_ALREADY_ON_DISK,
							String.valueOf(value));
				}
				this.writeFP(outRAF, fp);
				// we used one fp up, thus move to next one
				fp = itr.next();
			}
		}

		// write elements of remaining list
		if (eof) {
			while (fp > 0L) {
				this.writeFP(outRAF, fp);
				if (!itr.hasNext()) {
					break;
				}
				fp = itr.next();
			}
		} else {
			do {
				this.writeFP(outRAF, value);
				try {
					value = inRAF.readLong();
				} catch (EOFException e) {
					eof = true;
				}
			} while (!eof);
		}
		Assert.check(itr.reads() == buffLen, EC.GENERAL);

		// currIndex is amount of disk writes
		Assert.check(this.currIndex == indexLen - 1, EC.SYSTEM_INDEX_ERROR);

		// maintain object invariants
		this.fileCnt += buffLen;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#getTblCapacity()
	 */
	public long getTblCapacity() {
		return maxInMemoryCapacity;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#getCollisionBucketCnt()
	 */
	public long getCollisionBucketCnt() {
		return collisionBucket.size();
	}
	
	public class ByteBufferIterator {

		private final SortedSet<Long> collisionBucket;
		/**
		 * Number of elements in the buffer
		 */
		private long bufferElements;
		/**
		 * Total amount of elements in both the buffer as well as the collisionBucket. 
		 */
		private final long totalElements;
		/**
		 * The logical position is the position inside the {@link LongBuffer} and
		 * thus reflects a fingerprints
		 */
		private long logicalPosition = 0;
		/**
		 * Used to verify that the elements we hand out are strictly monotonic
		 * increasing.
		 */
		private long previous = -1l;
		/**
		 * Number of elements read with next()
		 */
		private long readElements = 0L;

		private long cache = -1L;
		private final Unsafe unsafe;

		public ByteBufferIterator(Unsafe u, long baseAddress, SortedSet<Long> collisionBucket, long expectedElements) {
			this.unsafe = u;
			this.logicalPosition = 0L;
			this.totalElements = expectedElements;
			
			this.collisionBucket = collisionBucket;
			this.bufferElements = expectedElements - this.collisionBucket.size();
		}

	    /**
	     * Returns the next element in the iteration.
	     *
	     * @return the next element in the iteration.
	     * @exception NoSuchElementException iteration has no more elements.
	     */
		public long next() {
			long result = -1l;

			if (cache < 0L && bufferElements > 0) {
				result = getNextFromBuffer();
				bufferElements--;
			} else {
				result = cache;
				cache = -1L;
			}

			if (!collisionBucket.isEmpty()) {
				long first = collisionBucket.first();
				if (result > first || result == -1L) {
					collisionBucket.remove(first);
					cache = result;
					result = first;
				}
			}
			
			// adhere to the general Iterator contract to fail fast and not hand out
			// meaningless values
			if (result == -1L) {
				throw new NoSuchElementException();
			}
			
			// hand out strictly monotonic increasing elements
			Assert.check(previous < result, EC.GENERAL);
			previous = result;
			
			// maintain read statistics
			readElements++;
			
			return result;
		}

		private long getNextFromBuffer() {
			sortNextBucket();
			
			long l = unsafe.getAddress(log2phy(logicalPosition));
			if (l > 0L) {
				unsafe.putAddress(log2phy(logicalPosition++), l | 0x8000000000000000L);
				return l;
			}
			
			while ((l = unsafe.getAddress(log2phy(logicalPosition))) <= 0L && logicalPosition < maxInMemoryCapacity) {
				// increment position to next bucket
				logicalPosition = logicalPosition + DiskFPSet.InitialBucketCapacity & 0xFFFFFFFFFFFFFFF0L;
				sortNextBucket();
			}
			
			if (l > 0L) {
				unsafe.putAddress(log2phy(logicalPosition++), l | 0x8000000000000000L);
				return l;
			}
			throw new NoSuchElementException();
		}

		// sort the current logical bucket if we reach the first slot of the
		// bucket
		private void sortNextBucket() {
			if (logicalPosition % DiskFPSet.InitialBucketCapacity == 0) {
				long[] longBuffer = new long[DiskFPSet.InitialBucketCapacity];
				int i = 0;
				for (; i < DiskFPSet.InitialBucketCapacity; i++) {
					long l = unsafe.getAddress(log2phy(logicalPosition + i));
					if (l <= 0L) {
						break;
					} else {
						longBuffer[i] = l;
					}
				}
				if (i > 0) {
					Arrays.sort(longBuffer, 0, i);
					for (int j = 0; j < i; j++) {
						unsafe.putAddress(log2phy(logicalPosition, j),
								longBuffer[j]);
					}
				}
			}
		}

	    /**
	     * Returns <tt>true</tt> if the iteration has more elements. (In other
	     * words, returns <tt>true</tt> if <tt>next</tt> would return an element
	     * rather than throwing an exception.)
	     *
	     * @return <tt>true</tt> if the iterator has more elements.
	     */
		public boolean hasNext() {
			// hasNext does not move the indices at all!
			return readElements < totalElements;
		}

		/**
		 * @return The number of element read with {@link TLCIterator#next()} since
		 *         the creation of this instance.
		 */
		public long reads() {
			return readElements;
		}
		
		/**
		 * @return The last element in the iteration.
	     * @exception NoSuchElementException if iteration is empty.
		 */
		public long getLast() {
			long tmpLogicalPosition = logicalPosition;
			logicalPosition = maxInMemoryCapacity - DiskFPSet.InitialBucketCapacity;
			sortNextBucket();

			// reverse the current bucket to obtain last element
			long l = 1L;
			while ((l = unsafe.getAddress(log2phy(logicalPosition-- + DiskFPSet.InitialBucketCapacity - 1))) <= 0L) {
				if (((logicalPosition - DiskFPSet.InitialBucketCapacity) & 0xFFFFFFFFFFFFFFF0L) == 0) {
					sortNextBucket();
				}
			}
			
			if (l > 0L) {
				logicalPosition = tmpLogicalPosition;
				return l;
			}
			throw new NoSuchElementException();
		}
		
		// prevent synthetic methods
		private long log2phy(long logicalAddress) {
			return OffHeapDiskFPSet.this.log2phy(logicalAddress);
		}
		private long log2phy(long bucketAddress, long inBucketAddress) {
			return OffHeapDiskFPSet.this.log2phy(bucketAddress, inBucketAddress);
		}
	}
}
