package org.hillview.targets;

import com.google.gson.JsonObject;
import org.hillview.*;
import org.hillview.dataset.api.IDataSet;
import org.hillview.dataset.api.IJson;
import org.hillview.dataset.api.ISketch;
import org.hillview.maps.FilterMap;
import org.hillview.sketches.*;
import org.hillview.table.ColumnDescription;
import org.hillview.table.PrivacySchema;
import org.hillview.table.Schema;
import org.hillview.table.api.ITable;
import org.hillview.table.filters.RangeFilterDescription;
import org.hillview.table.rows.PrivacyMetadata;
import org.hillview.utils.Converters;
import org.hillview.utils.HillviewLogger;
import org.hillview.utils.JsonList;
import org.hillview.utils.Utilities;

import javax.annotation.Nullable;
import javax.websocket.Session;
import java.util.HashMap;
import java.util.function.BiFunction;

/**
 * This class represents a remote dataset that can only be accessed using differentially-private operations.
 */
public class PrivateTableTarget extends RpcTarget {

    /* Used to send a reply immediately without running a sketch. */
    private static <S extends IJson> void constructAndSendReply(S result, RpcRequest request, RpcRequestContext context) {
        JsonObject json = new JsonObject();
        json.addProperty("done", 1.0);

        Session session = context.getSessionIfOpen();
        if (session == null)
            return;
        if (result == null)
            json.add("data", null);
        else
            json.add("data", result.toJsonTree());
        RpcReply reply = request.createReply(json);
        RpcServer.sendReply(reply, Converters.checkNull(session));
        RpcServer.requestCompleted(request, Converters.checkNull(session));
        request.syncCloseSession(session);
    }

    private final IDataSet<ITable> table;

    /* Global parameters for differentially-private histograms using the binary mechanism. */
    private PrivacySchema metadata;

    public long seed; // Global seed for PRNG

    PrivateTableTarget(IDataSet<ITable> table, HillviewComputation computation,
                       PrivacySchema metadata) {
        super(computation);
        this.table = table;
        this.registerObject();

        this.metadata = metadata;
    }

    public static class PrivacySummary implements IJson {
        public Schema schema;
        public int rowCount = 0;
        public PrivacySchema metadata;
    }

    private PrivacySummary addPrivateMetadata(SummarySketch.TableSummary summary) {
        PrivacySummary pSumm = new PrivacySummary();
        pSumm.schema = summary.schema;
        pSumm.metadata = this.metadata;
        return pSumm;
    }

    @HillviewRpc
    public void getSchema(RpcRequest request, RpcRequestContext context) {
        SummarySketch ss = new SummarySketch();
        this.runCompleteSketch(this.table, ss, (d, c) -> addPrivateMetadata(d), request, context);
    }

    static class PrivateHistogramArgs {
        ColumnDescription cd = new ColumnDescription();
        double samplingRate = 1.0; // Fix to exact count
        long seed;

        double min;
        double max;
        int bucketCount;

        DyadicHistogramBuckets getBuckets(PrivacySchema metadata) {
            if (!cd.kind.isNumeric()) throw new RuntimeException("Attempted to instantiate private buckets with non-numeric column");

            // This bucket class ensures that computed buckets fall on leaf boundaries.
            return new DyadicHistogramBuckets(this.min, this.max,
                    this.bucketCount, metadata.get(cd.name).granularity);
        }

        HistogramSketch getSketch(PrivacySchema metadata) {
            DyadicHistogramBuckets buckets = this.getBuckets(metadata);
            return new HistogramSketch(buckets, this.cd.name, this.samplingRate, this.seed);
        }

        // add noise to result
        BiFunction<Histogram, HillviewComputation, PrivateHistogram> addLaplaceNoise(PrivacySchema metadata) {
            double leaves = (max-min) / metadata.get(cd.name).granularity;
            double scale = (Math.log(leaves) / (metadata.get(cd.name).epsilon * Math.log(2)));

            return (e, c) -> {
                PrivateHistogram privateHist = new PrivateHistogram(e);
                privateHist.addDyadicLaplaceNoise(scale);
                return privateHist;
            };
        }
    }

    @HillviewRpc
    public void histogram(RpcRequest request, RpcRequestContext context) {
        PrivateHistogramArgs info = request.parseArgs(PrivateHistogramArgs.class);
        HistogramSketch sk = info.getSketch(metadata);
        this.runCompleteSketch(this.table, sk, info.addLaplaceNoise(metadata), request, context);
    }

    static class PrivateRangeArgs {
        // We don't currently support histograms on string columns.
        ColumnDescription cd = new ColumnDescription();

        // If min and max were specified in a filter step by the user.
        @Nullable
        Double min;
        @Nullable
        Double max;
    }

    // For numeric-valued private histograms, this function returns the global min/max
    // specified by the curator if no range is provided, and otherwise just returns
    // the user-provided range.
    @HillviewRpc
    public void getDataRanges1D(RpcRequest request, RpcRequestContext context) {
        PrivateTableTarget.PrivateRangeArgs[] args = request.parseArgs(PrivateTableTarget.PrivateRangeArgs[].class);
        assert args.length == 1;

        double min, max;
        PrivacyMetadata md = this.metadata.get(args[0].cd.name);
        if ( args[0].min == null ) {
            min = md.globalMin;
        } else {
            min = args[0].min;
        }

        if ( args[0].max == null ) {
            max = md.globalMax;
        } else {
            max = args[0].max;
        }

        DataRange retRange = new DataRange(min, max);
        retRange.presentCount = -1;
        retRange.missingCount = -1;

        JsonList<DataRange> rangeList = new JsonList<>();
        rangeList.add(retRange);
        constructAndSendReply(rangeList, request, context);
    }

    // This is just a dummy function in order to parallel the TableTargetAPI.
    // Since only a range filter is supported, we do not have to do any recomputation
    // on the underlying table; this will be done in the histogram request.
    @HillviewRpc
    public void filterRange(RpcRequest request, RpcRequestContext context) {
        constructAndSendReply(this, request, context);
    }
}
