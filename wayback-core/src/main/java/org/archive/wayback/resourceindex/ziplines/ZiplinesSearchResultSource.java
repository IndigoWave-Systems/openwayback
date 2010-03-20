/* ZiplinesSearchResultSource
 *
 * $Id$:
 *
 * Created on Nov 23, 2009.
 *
 * Copyright (C) 2006 Internet Archive.
 *
 * This file is part of Wayback.
 *
 * Wayback is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * any later version.
 *
 * Wayback is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with Wayback; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.archive.wayback.resourceindex.ziplines;

import it.unimi.dsi.mg4j.util.FrontCodedStringList;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.exception.ResourceIndexNotAvailableException;
import org.archive.wayback.resourceindex.SearchResultSource;
import org.archive.wayback.resourceindex.cdx.CDXFormatToSearchResultAdapter;
import org.archive.wayback.resourceindex.cdx.format.CDXFormat;
import org.archive.wayback.resourceindex.cdx.format.CDXFormatException;
import org.archive.wayback.util.AdaptedIterator;
import org.archive.wayback.util.CloseableIterator;
import org.archive.wayback.util.flatfile.FlatFile;

/**
 * A set of Ziplines files, which are CDX files specially compressed into a 
 * series of GZipMembers such that:
 * 
 * 1) each member is exactly 128K, padded using a GZip comment header
 * 2) each member contains complete lines: no line spans two GZip members
 * 
 * If the data put into these files is sorted, then the data within the files
 * can be uncompressed when needed, minimizing the total data to be uncompressed
 * 
 * This SearchResultSource assumes a set of alphabetically partitioned Ziplined
 * CDX files, so that each file is sorted, and no regions overlap.
 * 
 * This class takes 2 files as input:
 * 1) a specially constructed map of the first N bytes of data from each GZip
 *      member, and the filename and offset of that GZip member.
 * 2) a mapping of filenames to URLs
 * 
 * Data from #1 is actually stored in a serialized 
 * 
 * 
 * 
 * @author brad
 *
 */
public class ZiplinesSearchResultSource implements SearchResultSource {

	/**
	 * Local path containing map of URL,TIMESTAMP,CHUNK,OFFSET for each 128K chunk
	 */
	private String chunkIndexPath = null;
	private FlatFile chunkIndex = null;
	/**
	 * Local path containing URL for each CHUNK
	 */
	private String chunkMapPath = null;
	private HashMap<String,String> chunkMap = null;
	private CDXFormat format = null;
	
	public ZiplinesSearchResultSource() {
	}
	public ZiplinesSearchResultSource(CDXFormat format) {
		this.format = format;
	}
	public void init() throws IOException {
		chunkMap = new HashMap<String, String>();
		FlatFile ff = new FlatFile(chunkMapPath);
		CloseableIterator<String> lines = ff.getSequentialIterator();
		while(lines.hasNext()) {
			String line = lines.next();
			String[] parts = line.split("\\s");
			if(parts.length != 2) {
				throw new IOException("Bad line(" + line +") in (" + 
						chunkMapPath + ")");
			}
			chunkMap.put(parts[0],parts[1]);
		}
		lines.close();
		chunkIndex = new FlatFile(chunkIndexPath);
	}
	protected CloseableIterator<CaptureSearchResult> adaptIterator(Iterator<String> itr) 
	throws IOException {
		return new AdaptedIterator<String,CaptureSearchResult>(itr,
				new CDXFormatToSearchResultAdapter(format));
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.resourceindex.SearchResultSource#cleanup(org.archive.wayback.util.CloseableIterator)
	 */
	public void cleanup(CloseableIterator<CaptureSearchResult> c)
			throws IOException {
		c.close();
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.resourceindex.SearchResultSource#getPrefixIterator(java.lang.String)
	 */
	public CloseableIterator<CaptureSearchResult> getPrefixIterator(
			String prefix) throws ResourceIndexNotAvailableException {
		try {
			return adaptIterator(getStringPrefixIterator(prefix));
		} catch (IOException e) {
			throw new ResourceIndexNotAvailableException(e.getMessage());
		}
	}
	
	public Iterator<String> getStringPrefixIterator(String prefix) throws ResourceIndexNotAvailableException, IOException {
		CloseableIterator<String> itr = chunkIndex.getRecordIteratorLT(prefix);
		ArrayList<ZiplinedBlock> blocks = new ArrayList<ZiplinedBlock>();
		boolean first = true;
		while(itr.hasNext()) {
			String blockDescriptor = itr.next();
			String parts[] = blockDescriptor.split("\t");
			if(parts.length != 3) {
				throw new ResourceIndexNotAvailableException("Bad line(" + 
						blockDescriptor + ")");
			}
			// only compare the correct length:
			String prefCmp = prefix;
			String blockCmp = parts[0];
//			if(prefCmp.length() < blockCmp.length()) {
//				blockCmp = blockCmp.substring(0,prefCmp.length());
//			} else {
//				prefCmp = prefCmp.substring(0,blockCmp.length());
//			}
			if(first) {
				// always add first:
				first = false;
//			} else if(blockCmp.compareTo(prefCmp) > 0) {
			} else if(!blockCmp.startsWith(prefCmp)) {
				// all done;
				break;
			}
			// add this and keep lookin...
			String url = chunkMap.get(parts[1]);
			long offset = Long.parseLong(parts[2]);
			blocks.add(new ZiplinedBlock(url, offset));
		}
		itr.close();
		return new StringPrefixIterator(new ZiplinesChunkIterator(blocks),prefix);
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.resourceindex.SearchResultSource#getPrefixReverseIterator(java.lang.String)
	 */
	public CloseableIterator<CaptureSearchResult> getPrefixReverseIterator(
			String prefix) throws ResourceIndexNotAvailableException {
		throw new ResourceIndexNotAvailableException("unsupported op");
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.resourceindex.SearchResultSource#shutdown()
	 */
	public void shutdown() throws IOException {
		// no-op..
	}
	/**
	 * @return the format
	 */
	public CDXFormat getFormat() {
		return format;
	}
	/**
	 * @param format the format to set
	 */
	public void setFormat(CDXFormat format) {
		this.format = format;
	}
	/**
	 * @return the chunkIndexPath
	 */
	public String getChunkIndexPath() {
		return chunkIndexPath;
	}
	/**
	 * @param chunkIndexPath the chunkIndexPath to set
	 */
	public void setChunkIndexPath(String chunkIndexPath) {
		this.chunkIndexPath = chunkIndexPath;
	}
	/**
	 * @return the chunkMapPath
	 */
	public String getChunkMapPath() {
		return chunkMapPath;
	}
	/**
	 * @param chunkMapPath the chunkMapPath to set
	 */
	public void setChunkMapPath(String chunkMapPath) {
		this.chunkMapPath = chunkMapPath;
	}

}