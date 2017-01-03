package org.hiero.sketch.spreadsheet;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.hiero.sketch.dataset.api.ISketch;
import org.hiero.sketch.dataset.api.PartialResult;
import org.hiero.sketch.table.*;
import org.hiero.sketch.table.api.IColumn;
import org.hiero.sketch.table.api.IMembershipSet;
import org.hiero.sketch.table.api.IRowOrder;
import rx.Observable;

import java.security.InvalidParameterException;

/**
 * QuantileSketch is used to compute Quantiles over a distributed data set according to a prescribed
 * ordering of the elements. Quantiles are represented using the QuantileList class.
 * QuantileSketch provides two main methods:
 * - getQuantile: It creates a QuantileList from an input Table
 * - add: It combines two QuantileLists created from disjoint dataSets to create a single new
 * QuantileList, that captures Quantile information for the union.
 * It stores the following objects:
 * - colSortOrder: the order and orientation of the columns to define the sorted order.
 * - resolution: the desired number of quantiles.
 */
public class QuantileSketch implements ISketch<Table, QuantileList> {
    private final RecordOrder colSortOrder;
    private final int resolution;

    /**
     * @param sortOrder The list of column orientations.
     * @param resolution Number of buckets: percentiles correspond to 100 buckets etc.
     */
    public QuantileSketch(final RecordOrder sortOrder, final int resolution) {
        this.colSortOrder = sortOrder;
        this.resolution = resolution;
    }

    /**
     * Given a table and a desired resolution for percentiles, return the answer for a sample.
     * The size of the sample is resolution*perBin, perBin is set to 100 by default.
     * @param data The input data on which we want to compute Quantiles.
     * @return A table of size resolution, whose ith entry is ranked approximately i/resolution.
     */
    public QuantileList getQuantile(final Table data) {
        /* Sample a set of rows from the table. */
        final int perBin = 100;
        final int dataSize = data.getNumOfRows();
        /* Sample, then sort the sampled rows. */
        final IMembershipSet sampleSet = data.members.sample(this.resolution * perBin);
        final Table sampleTable = data.compress(sampleSet);
        final Integer[] order = this.colSortOrder.getSortedRowOrder(sampleTable);
        /* Pick equally spaced elements as the sample quantiles.
        *  Our estimate for the rank of element i is the i*step. */
        final int[] quantile = new int[this.resolution];
        final WinsAndLosses[] winsAndLosses = new WinsAndLosses[this.resolution];
        /* Number of samples might be less than resolution*perBin, because of repetitions */
        final int sampleStep = sampleTable.getNumOfRows()/(resolution + 1);
        final int dataStep = dataSize/(resolution +1);
        for (int i = 0; i < resolution; i++) {
            quantile[i] = order[ (i+1)* sampleStep - 1];
            winsAndLosses[i] = new WinsAndLosses((i +1) * dataStep, (resolution - i) * dataStep);
        }
        final IRowOrder quantileMembers = new ArrayRowOrder(quantile);
        return new QuantileList(sampleTable.compress(colSortOrder.toSubSchema(), quantileMembers),
                winsAndLosses, dataSize);
    }

    /**
     * Given two Columns left and right, merge them to a single Column, using the Boolean
     * array mergeLeft which represents the order in which elements merge.
     * mergeLeft[i] = true means the i^th element comes from the left column.
     * @param left The left column
     * @param right The right column
     * @param mergeLeft The order in which to merge the two columns.
     * @return The merged column.
     */
    private ObjectArrayColumn mergeColumns(@NonNull final IColumn left,
                                           @NonNull final IColumn right,
                                           @NonNull final boolean[] mergeLeft) {
        if (mergeLeft.length != (left.sizeInRows() + right.sizeInRows())) {
            throw new InvalidParameterException("Length of mergeOrder must equal " +
                    "sum of lengths of the columns");
        }
        final ObjectArrayColumn merged = new
                ObjectArrayColumn(left.getDescription(), mergeLeft.length);
        int i = 0, j = 0, k = 0;
        while (k < mergeLeft.length) {
            if (mergeLeft[k]) {
                merged.set(k, left.getObject(i));
                i++;
            } else {
                merged.set(k, right.getObject(j));
                j++;
            }
            k++;
        }
        return merged;
    }
    /**
     * Given two QuantileLists left and right, compute the number of Wins and Losses for the
     * elements in the merged order, represented by Boolean array mergeLeft which represents the
     * order in which elements merge.
     * @param left The left column
     * @param right The right column
     * @param mergeLeft The order in which to merge the two columns.
     * @return The ApproxRanks (wins and losses) for elements in the merged QuantileList.
     */
    private WinsAndLosses[] mergeRanks(@NonNull final QuantileList left,
                                       @NonNull final QuantileList right,
                                       @NonNull final boolean[] mergeLeft) {
        final int length = left.getQuantileSize() + right.getQuantileSize();
        final WinsAndLosses[] mergedRank = new WinsAndLosses[length];
        int i = 0, j = 0, lower, upper;
        for (int k = 0; k < length; k++) {
            if (mergeLeft[k]) { /* i lost to j, so we insert i next*/
                 /* Entry i gets its own Wins + the Wins for the biggest entry on
                 *  the right that lost to it. This is either the Wins of j-1, or 0 if i beat
                 *  nobody on the right (which means j = 0);*/
                lower = left.getWins(i) + ((j > 0) ? right.getWins(j - 1) : 0);
                /*  Similarly, its Losses are its own Losses + the Losses of the
                 *  smallest element on the right that beat it. This is the Losses of j if the
                 *  right hand side has not been exhausted, in which case it is 0. */
                upper = left.getLosses(i) +
                        ((j < right.getQuantileSize()) ? right.getLosses(j) : 0);
                mergedRank[k] = new WinsAndLosses(lower, upper);
                i++;
            } else {
                lower = right.getWins(j) + ((i > 0) ? left.getWins(i - 1) : 0);
                upper = right.getLosses(j) +
                        ((i < left.getQuantileSize()) ? left.getLosses(i) : 0);
                mergedRank[k] = new WinsAndLosses(lower, upper);
                j++;
            }
        }
        return mergedRank;
    }


    /**
     * Given two QuantileLists left and right, merge them to a single QuantileList.
     * @param left The left Quantile
     * @param right The right Quantile
     * @return The merged Quantile
     */
    @Override
    public QuantileList add(@NonNull final QuantileList left, @NonNull final QuantileList right) {
         if (!left.getSchema().equals(right.getSchema()))
            throw new RuntimeException("The schemas do not match.");
        final int width = left.getSchema().getColumnCount();
        final int length = left.getQuantileSize() + right.getQuantileSize();
        final ObjectArrayColumn[] mergedCol = new ObjectArrayColumn[width];
        final boolean[] mergeLeft = this.colSortOrder.getMergeOrder(left.quantile, right.quantile);
        int i = 0;
        for (String colName: left.getSchema().getColumnNames()) {
            mergedCol[i] = mergeColumns(left.getColumn(colName),
                    right.getColumn(colName), mergeLeft);
            i++;
        }
        final IMembershipSet full = new FullMembership(length);
        final Table mergedTable = new Table(left.getSchema(), mergedCol, full);
        final WinsAndLosses[] mergedRank = mergeRanks(left, right, mergeLeft);
        final int mergedDataSize = left.getDataSize() + right.getDataSize();
        final int slack = 10;
        /* The returned quantileList can be of size up to slack* resolution*/
        return new QuantileList(mergedTable, mergedRank, mergedDataSize).
                compressExact(slack*this.resolution);
    }

    @Override
    public QuantileList zero() {
        return new QuantileList(this.colSortOrder.toSchema());
    }

    @Override
    public Observable<PartialResult<QuantileList>> create(final Table data) {
        QuantileList q = this.getQuantile(data);
        PartialResult<QuantileList> result = new PartialResult<>(1.0, q);
        return Observable.just(result);
    }
}