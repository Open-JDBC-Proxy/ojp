package org.openjproxy.grpc.server.paging;

/**
 * Holds pagination information extracted from a SQL query.
 * Supports multi-dialect pagination (LIMIT/OFFSET, FETCH NEXT, ROWNUM, etc.)
 */
public class PageInfo {

    private final long currentOffset;
    private final long pageSize;

    public PageInfo(long currentOffset, long pageSize) {
        this.currentOffset = currentOffset;
        this.pageSize = pageSize;
    }

    /**
     * Returns the OFFSET value for the current page.
     */
    public long getCurrentOffset() {
        return currentOffset;
    }

    /**
     * Returns the number of rows per page (LIMIT / FETCH size).
     */
    public long getPageSize() {
        return pageSize;
    }

    /**
     * Returns the OFFSET value for the next page.
     */
    public long getNextPageOffset() {
        return currentOffset + pageSize;
    }

    /**
     * Returns true if this is the first page (offset == 0).
     */
    public boolean isFirstPage() {
        return currentOffset == 0;
    }

    @Override
    public String toString() {
        return "PageInfo{currentOffset=" + currentOffset + ", pageSize=" + pageSize + "}";
    }
}
