/*
 * Copyright (c) 2017 VMware Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hillview.storage;

import org.hillview.sketches.results.DoubleHistogramBuckets;
import org.hillview.sketches.results.IHistogramBuckets;
import org.hillview.sketches.results.StringHistogramBuckets;
import org.hillview.table.ColumnDescription;
import org.hillview.table.api.ContentsKind;
import org.hillview.table.columns.ColumnQuantization;
import org.hillview.table.columns.DoubleColumnQuantization;
import org.hillview.table.columns.StringColumnQuantization;
import org.hillview.utils.Converters;
import org.hillview.utils.Linq;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public class MySqlJdbcConnection extends JdbcConnection {
    MySqlJdbcConnection(JdbcConnectionInformation conn) {
        super('&', '?', conn);
    }

    class MySqlCodeGenerator {
        public final ColumnDescription cd;
        @Nullable final ColumnQuantization quantization;
        @Nullable final IHistogramBuckets buckets;
        final DateTimeFormatter dateFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault());;

        MySqlCodeGenerator(ColumnDescription cd,
                           @Nullable ColumnQuantization quantization,
                           @Nullable IHistogramBuckets buckets) {
            this.cd = cd;
            this.quantization = quantization;
            this.buckets = buckets;
        }

        MySqlCodeGenerator(ColumnDescription cd,
                           @Nullable ColumnQuantization quantization) {
            this(cd, quantization, null);
        }

        private StringColumnQuantization getStringQuantization() {
            Converters.checkNull(this.quantization);
            if (!(this.quantization instanceof StringColumnQuantization))
                throw new RuntimeException("Quantization is not String");
            return (StringColumnQuantization)this.quantization;
        }

        private DoubleColumnQuantization getDoubleQuantization() {
            Converters.checkNull(this.quantization);
            if (!(this.quantization instanceof DoubleColumnQuantization))
                throw new RuntimeException("Quantization is not Double");
            return (DoubleColumnQuantization)this.quantization;
        }

        private StringHistogramBuckets getStringBuckets() {
            Converters.checkNull(this.buckets);
            if (!(this.buckets instanceof StringHistogramBuckets))
                throw new RuntimeException("Buckets are not String");
            return (StringHistogramBuckets)this.buckets;
        }

        private DoubleHistogramBuckets getDoubleBuckets() {
            Converters.checkNull(this.buckets);
            if (!(this.buckets instanceof DoubleHistogramBuckets))
                throw new RuntimeException("Buckets are not Double");
            return (DoubleHistogramBuckets)this.buckets;
        }

        /**
         * A SQL expression that quantizes the value in the column.
         */
        String quantizedValue() {
            if (quantization == null)
                return cd.name;
            if (this.cd.kind.isString()) {
                StringColumnQuantization q = this.getStringQuantization();
                return searchInterval(0, q.leftBoundaries.length,
                        q.leftBoundaries, cd.name, s -> "BINARY '" + s + "'", false);
            } else if (this.cd.kind == ContentsKind.Date) {
                DoubleColumnQuantization q = this.getDoubleQuantization();
                Instant minDate = Converters.toDate(q.globalMin);
                Instant maxDate = Converters.toDate(q.globalMax);
                String minString = this.dateFormatter.format(minDate);
                String maxString = this.dateFormatter.format(maxDate);
                double g = 1000.0 * q.granularity;
                return "TIMESTAMPADD(MICROSECOND, FLOOR(TIMESTAMPDIFF(MICROSECOND, " +
                        "'" + minString + "', " + cd.name + ") / " + g + ") * " + g + ", '" +
                        minString + "')";
            } else {
                if (!this.cd.kind.isNumeric())
                    throw new RuntimeException("Unexpected kind " + this.cd.kind);
                DoubleColumnQuantization q = this.getDoubleQuantization();
                return q.globalMin + " + FLOOR((" + cd.name + " - " +
                        q.globalMin + ") / " + q.granularity + ") * " +
                        q.granularity;
            }
        }

        String table() {
            return Converters.checkNull(MySqlJdbcConnection.this.info.table);
        }

        private <T> String searchInterval(int leftIndex, int rightIndex,
                                          T[] boundaries, String column,
                                          Function<T, String> convert,
                                          boolean toIndex) {
            // We synthesize a binary search tree
            if (leftIndex == rightIndex - 1)
                if (toIndex)
                    return Integer.toString(leftIndex);
                else
                    return this.quote(boundaries[leftIndex].toString());
            int mid = leftIndex + (rightIndex - leftIndex) / 2;
            String result = "if(" + column + " < " + convert.apply(boundaries[mid]) + ", ";
            String recLeft = searchInterval(leftIndex, mid, boundaries, column, convert, toIndex);
            String recRight = searchInterval(mid, rightIndex, boundaries, column, convert, toIndex);
            return result + recLeft + ", " + recRight + ")";
        }

        String quote(String value) {
            if (this.cd.kind.isString()) {
                return "BINARY '" + value + "'";
            } else if (this.cd.kind == ContentsKind.Date) {
                return "'" + value + "'";
            } else {
                return value;
            }
        }

        String quoteDate(double value) {
            Instant i = Converters.toDate(value);
            return this.quote(this.dateFormatter.format(i));
        }

        String min() {
            if (quantization == null)
                return "";
            if (this.cd.kind == ContentsKind.Date) {
                DoubleColumnQuantization q = this.getDoubleQuantization();
                return this.quoteDate(q.globalMin);
            } else {
                Converters.checkNull(this.quantization);
                return quote(this.quantization.minAsString());
            }
        }

        String max() {
            if (quantization == null)
                return "";
            if (this.cd.kind == ContentsKind.Date) {
                DoubleColumnQuantization q = this.getDoubleQuantization();
                return this.quoteDate(q.globalMax);
            } else {
                Converters.checkNull(this.quantization);
                return this.quote(this.quantization.maxAsString());
            }
        }

        String quantizeBounds() {
            if (quantization == null)
                return "true";
            return cd.name + " between " + this.min() + " and " + this.max();
        }

        String quantizeTable() {
            if (quantization == null)
                return this.table();
            return "(select " + this.quantizedValue() + " AS " + cd.name + " from " + this.table() +
                        " where " + this.quantizeBounds() + ") tmpq";
        }

        private String filterQuantizeBounds() {
            if (this.quantization != null) {
                return "(select " + this.cd.name +
                        " from " + this.table() + " where " +
                        this.quantizeBounds() + ") tmpf";
            }
            return this.table();
        }

        /**
         * Returns an expression that computes a bucket for a value in the column
         * using this.buckets.
         */
        public String getBucket() {
            if (this.cd.kind.isString()) {
                StringHistogramBuckets s = this.getStringBuckets();
                return searchInterval(0, s.getBucketCount(), s.leftBoundaries, cd.name,
                                        v -> "BINARY '" + v + "'", true);
            } else if (this.cd.kind == ContentsKind.Date) {
                DoubleHistogramBuckets buckets = this.getDoubleBuckets();
                Instant minDate = Converters.toDate(buckets.minValue);
                Instant maxDate = Converters.toDate(buckets.maxValue);
                double min = minDate.toEpochMilli() * 1000.0;
                double max = maxDate.toEpochMilli() * 1000.0;
                double scale = (double)buckets.bucketCount / (max - min);
                return "CAST(FLOOR(TIMESTAMPDIFF(MICROSECOND, '" + this.dateFormatter.format(minDate)
                        + "', " + cd.name + ") * " +
                        scale + ") as UNSIGNED)";
            } else {
                if (!this.cd.kind.isNumeric())
                    throw new RuntimeException("Unexpected kind " + this.cd.kind);
                DoubleHistogramBuckets buckets = this.getDoubleBuckets();
                double scale = (double)buckets.bucketCount / buckets.range;
                return "CAST(FLOOR((" + this.cd.name + " - " + buckets.minValue + ") * " + scale +
                        ") as UNSIGNED)";
            }
        }

        String minBucket() {
            if (this.cd.kind == ContentsKind.Date) {
                return this.quoteDate(this.getDoubleBuckets().minValue);
            } else if (this.cd.kind.isString()) {
                return this.quote(this.getStringBuckets().minValue);
            } else {
                return this.quote(Double.toString(this.getDoubleBuckets().minValue));
            }
        }

        String maxBucket() {
            if (this.cd.kind == ContentsKind.Date) {
                return this.quoteDate(this.getDoubleBuckets().maxValue);
            } else if (this.cd.kind.isString()) {
                return this.quote(Converters.checkNull(this.getStringBuckets().maxValue));
            } else {
                return this.quote(Double.toString(this.getDoubleBuckets().maxValue));
            }
        }

        String bucketBounds(boolean withWhere) {
            String result = "";
            if (this.cd.kind.isString() && this.getStringBuckets().maxValue == null)
                return result;
            if (withWhere)
                result += " where ";
            result += cd.name + " between " + this.minBucket() + " and " + this.maxBucket();
            return result;
        }

        String getHistogramQuery() {
            String bucket = this.getBucket();
            String quantize = this.quantizeTable();
            String bounds = this.bucketBounds(true);
            return "select bucket, count(bucket) from (select " +
                    bucket + " as bucket from " + quantize +
                    bounds + ") tmph group by bucket";
        }
    }

    @Override
    public String getQueryToReadTable(int rowCount) {
        String result = "SELECT * FROM " + Converters.checkNull(this.info.table);
        if (rowCount >= 0)
            result += " LIMIT " + rowCount;
        return result;
    }

    @Override
    public String getURL() {
        this.addParameter("useLegacyDatetimeCode", "true");
        this.addParameter("useSSL", "false");
        StringBuilder builder = new StringBuilder();
        this.addBaseUrl(builder);
        this.appendParametersToUrl(builder);
        return builder.toString();
    }

    @Override
    public String getQueryForNumericRange(ColumnDescription cd,
                                          @Nullable DoubleColumnQuantization quantization) {
        MySqlCodeGenerator generator = new MySqlCodeGenerator(cd, quantization);
        return "select MIN(" + cd.name + ") as min, MAX(" + cd.name +
                ") as max, COUNT(*) as total, COUNT(" + cd.name + ") as nonnulls from " +
                generator.filterQuantizeBounds();
    }

    @Override
    public String getQueryForDistinct(String column) {
        Converters.checkNull(this.info.table);
        // BINARY is needed to force mysql to do a case-sensitive comparison
        return "SELECT CAST(" + column + " AS CHAR) FROM " +
                "(SELECT DISTINCT BINARY " + column + " AS " + column + " FROM " +
                this.info.table + " ORDER BY BINARY " + column + ") tmpd";
    }

    @Override
    public String getQueryForCounts(ColumnDescription cd, @Nullable ColumnQuantization quantization) {
        MySqlCodeGenerator generator = new MySqlCodeGenerator(cd, quantization);
        return "select COUNT(*) as total, COUNT(" + cd.name + ") as nonnulls from " +
                generator.filterQuantizeBounds();
    }

    @Override
    public String getQueryForHistogram(ColumnDescription cd, IHistogramBuckets buckets,
                                       @Nullable ColumnQuantization quantization) {
        MySqlCodeGenerator generator = new MySqlCodeGenerator(cd, quantization, buckets);
        return generator.getHistogramQuery();
    }

    private static String makeWhere(String condition0, String condition1) {
        if (condition0.isEmpty())
            condition0 = "true";
        if (condition1.isEmpty())
            condition1 = "true";
        return " where (" + condition0 + ") and (" + condition1 + ")";
    }

    private String makeQuantizedTable(MySqlCodeGenerator[] gens) {
        return "(select " +
                String.join(",", Linq.map(gens, g -> g.cd.name, String.class)) +
                " from " + this.info.table + " where " +
                String.join(" and ", Linq.map(gens, MySqlCodeGenerator::quantizeBounds, String.class)) +
                ")";
    }

    public String getQueryForHeatmap(ColumnDescription cd0, ColumnDescription cd1,
                                     IHistogramBuckets buckets0, IHistogramBuckets buckets1,
                                     @Nullable ColumnQuantization quantization0,
                                     @Nullable ColumnQuantization quantization1) {
        MySqlCodeGenerator g0 = new MySqlCodeGenerator(cd0, quantization0, buckets0);
        MySqlCodeGenerator g1 = new MySqlCodeGenerator(cd1, quantization1, buckets1);
        String b0 = g0.getBucket();
        String b1 = g1.getBucket();
        String bb0 = g0.bucketBounds(false);
        String bb1 = g1.bucketBounds(false);
        String bounds = makeWhere(bb0, bb1);
        String quantization = makeQuantizedTable(new MySqlCodeGenerator[] { g0, g1 });
        return "select (bucket0 << 16) | bucket1, count(bucket0 << 16 | bucket1) from (" +
                "select " + b0 + " as bucket0, " + b1 + " as bucket1 from " +
                "(" + quantization + ") tmph1" +
                bounds + ") tmph2 group by (bucket0 << 16) | bucket1";
    }
}
