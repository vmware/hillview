/*
 * Copyright (c) 2017 VMWare Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { HistogramViewBase, BucketDialog, AnyScale } from "./histogramBase";
import {
    ColumnDescription, Schema, RecordOrder, ColumnAndRange, FilterDescription,
    ZipReceiver, RemoteTableRenderer, BasicColStats
} from "./tableData";
import {TableView, TableRenderer} from "./table";
import {FullPage, significantDigits, formatNumber, translateString, Resolution, Rectangle} from "./ui";
import {TopMenu, TopSubMenu} from "./menu";
import d3 = require('d3');
import {reorder, Converters, transpose, ICancellable, PartialResult} from "./util";
import {AxisData, HeatMapData, Range2DCollector} from "./heatMap";
import {combineMenu, CombineOperators, SelectedObject, Renderer} from "./rpc";

interface Rect {
    x: number;
    y: number;
    index: number;
    height: number;
}

export class Histogram2DView extends HistogramViewBase {
    protected currentData: {
        xData: AxisData;
        yData: AxisData;
        missingData: number;
        data: number[][];
        xPoints: number;
        yPoints: number;
        visiblePoints: number;
    };
    protected normalized: boolean;
    protected selectingLegend: boolean;
    protected legendRect: Rectangle;  // legend position on the screen
    protected legend: any;  // a d3 object
    protected legendScale: AnyScale;

    constructor(remoteObjectId: string, protected tableSchema: Schema, page: FullPage) {
        super(remoteObjectId, tableSchema, page);
        let menu = new TopMenu( [
            { text: "View", subMenu: new TopSubMenu([
                { text: "refresh", action: () => { this.refresh(); } },
                { text: "table", action: () => this.showTable() },
                { text: "#buckets", action: () => this.chooseBuckets() },
                { text: "swap axes", action: () => { this.swapAxes(); } },
                { text: "percent/value", action: () => { this.normalized = !this.normalized; this.refresh(); } },
            ]) },
            {
                text: "Combine", subMenu: combineMenu(this)
            }
        ]);

        this.normalized = false;
        this.selectingLegend = true;
        this.topLevel.insertBefore(menu.getHTMLRepresentation(), this.topLevel.children[0]);
    }

    // combine two views according to some operation
    combine(how: CombineOperators): void {
        let r = SelectedObject.current.getSelected();
        if (r == null) {
            this.page.reportError("No view selected");
            return;
        }

        let rr = this.createZipRequest(r);
        let renderer = (page: FullPage, operation: ICancellable) => {
            return new Make2DHistogram(
                page, operation,
                [this.currentData.xData.description, this.currentData.yData.description],
                this.tableSchema, false);
        };
        rr.invoke(new ZipReceiver(this.getPage(), rr, how, renderer));
    }

    public swapAxes(): void {
        if (this.currentData == null)
            return;
        this.updateView(
            transpose(this.currentData.data),
            this.currentData.yData,
            this.currentData.xData,
            this.currentData.missingData,
            0);
    }

    changeBuckets(bucketCount: number): void {
        let arg0: ColumnAndRange = {
            columnName: this.currentData.xData.description.name,
            min: this.currentData.xData.stats.min,
            max: this.currentData.xData.stats.max,
            bucketCount: bucketCount,
            samplingRate: HistogramViewBase.samplingRate(bucketCount, this.currentData.visiblePoints),
            cdfBucketCount: 0,
            bucketBoundaries: null // TODO
        };
        let arg1: ColumnAndRange = {
            columnName: this.currentData.yData.description.name,
            min: this.currentData.yData.stats.min,
            max: this.currentData.yData.stats.max,
            samplingRate: HistogramViewBase.samplingRate(this.currentData.yPoints, this.currentData.visiblePoints),
            bucketCount: this.currentData.yPoints,
            cdfBucketCount: 0,
            bucketBoundaries: null // TODO
        };
        let rr = this.createHeatMapRequest(arg0, arg1);
        let renderer = new Histogram2DRenderer(this.page,
            this.remoteObjectId, this.tableSchema,
            [this.currentData.xData.description, this.currentData.yData.description],
            [this.currentData.xData.stats, this.currentData.yData.stats], rr);
        rr.invoke(renderer);
    }

    chooseBuckets(): void {
        if (this.currentData == null)
            return;

        let bucketDialog = new BucketDialog();
        bucketDialog.setAction(() => this.changeBuckets(bucketDialog.getBucketCount()));
        bucketDialog.show();
    }

    public refresh(): void {
        if (this.currentData == null)
            return;
        this.updateView(
            this.currentData.data,
            this.currentData.xData,
            this.currentData.yData,
            this.currentData.missingData,
            0);
    }

    protected onMouseMove(): void {
        let position = d3.mouse(this.chart.node());
        let mouseX = position[0];
        let mouseY = position[1];

        let x : number | Date = 0;
        if (this.xScale != null)
            x = this.xScale.invert(position[0]);

        if (this.currentData.xData.description.kind == "Integer")
            x = Math.round(<number>x);
        let xs = String(x);
        if (this.currentData.xData.description.kind == "Category")
            xs = this.currentData.xData.allStrings.get(<number>x);
        else if (this.currentData.xData.description.kind == "Integer" ||
            this.currentData.xData.description.kind == "Double")
            xs = significantDigits(<number>x);
        let y = Math.round(this.yScale.invert(position[1]));
        let ys = significantDigits(y);
        this.xLabel.textContent = "x=" + xs;
        this.yLabel.textContent = "y=" + ys;

        this.xDot.attr("cx", mouseX + Resolution.leftMargin);
        this.yDot.attr("cy", mouseY + Resolution.topMargin);

        /*
        if (this.currentData.cdfSum != null) {
            // determine mouse position on cdf curve
            // we have to take into account the adjustment
            let cdfX = (mouseX - this.adjustment / 2) * this.currentData.cdfSum.length /
                (this.chartResolution.width - this.adjustment);
            let pos = 0;
            if (cdfX < 0) {
                pos = 0;
            } else if (cdfX >= this.currentData.cdfSum.length) {
                pos = 1;
            } else {
                let cdfPosition = this.currentData.cdfSum[Math.floor(cdfX)];
                pos = cdfPosition / this.currentData.stats.presentCount;
            }

            this.cdfDot.attr("cx", mouseX + Resolution.leftMargin);
            this.cdfDot.attr("cy", (1 - pos) * this.chartResolution.height + Resolution.topMargin);
            let perc = percent(pos);
            this.cdfLabel.textContent = "cdf=" + perc;
        }
        */
    }

    public updateView(data: number[][], xData: AxisData, yData: AxisData,
                      missingData: number, elapsedMs: number) : void {
        this.page.reportError("Operation took " + significantDigits(elapsedMs / 1000) + " seconds");
        if (data == null || data.length == 0) {
            this.page.reportError("No data to display");
            return;
        }
        let xPoints = data.length;
        let yRectangles = data[0].length;
        if (yRectangles == 0) {
            this.page.reportError("No data to display");
            return;
        }
        this.currentData = {
            data: data,
            xData: xData,
            yData: yData,
            missingData: missingData,
            visiblePoints: 0,
            xPoints: xPoints,
            yPoints: yRectangles
        };

        let canvasSize = Resolution.getCanvasSize(this.page);
        this.chartSize = Resolution.getChartSize(this.page);

        /*
         let counts = h.buckets;
         let bucketCount = counts.length;
         let max = d3.max(counts);

         // prefix sum for cdf
         let cdfData: number[] = [];
         if (cdf != null) {
         this.currentData.cdfSum = [];

         let sum = 0;
         for (let i in cdf.buckets) {
         sum += cdf.buckets[i];
         this.currentData.cdfSum.push(sum);
         }

         let point = 0;
         for (let i in this.currentData.cdfSum) {
         cdfData.push(point);
         point = this.currentData.cdfSum[i] * max / stats.presentCount;
         cdfData.push(point);
         }
         }
         */

        if (this.canvas != null)
            this.canvas.remove();

        let counts: number[] = [];

        let max: number = 0;
        let rects: Rect[] = [];
        for (let x = 0; x < data.length; x++) {
            let yTotal = 0;
            for (let y = 0; y < data[x].length; y++) {
                let v = data[x][y];
                this.currentData.visiblePoints += v;
                if (v != 0) {
                    let rec: Rect = {
                        x: x,
                        y: yTotal,
                        index: y,
                        height: v
                    };
                    rects.push(rec);
                }
                yTotal += v;
            }
            if (yTotal > max)
                max = yTotal;
            counts.push(yTotal);
        }

        if (max <= 0) {
            this.page.reportError("No data");
            return;
        }

        let drag = d3.drag()
            .on("start", () => this.dragStart())
            .on("drag", () => this.dragMove())
            .on("end", () => this.dragEnd());

        this.canvas = d3.select(this.chartDiv)
            .append("svg")
            .attr("id", "canvas")
            .call(drag)
            .attr("width", canvasSize.width)
            .attr("border", 1)
            .attr("height", canvasSize.height)
            .attr("cursor", "crosshair");

        this.canvas.on("mousemove", () => this.onMouseMove());

        // The chart uses a fragment of the canvas offset by the margins
        this.chart = this.canvas
            .append("g")
            .attr("transform", translateString(Resolution.leftMargin, Resolution.topMargin));

        this.yScale = d3.scaleLinear()
            .range([this.chartSize.height, 0]);
        if (this.normalized)
            this.yScale.domain([0, 100]);
        else
            this.yScale.domain([0, max]);
        let yAxis = d3.axisLeft(this.yScale)
            .tickFormat(d3.format(".2s"));

        let cd = xData.description;
        let bucketCount = xPoints;
        let minRange = xData.stats.min;
        let maxRange = xData.stats.max;
        this.adjustment = 0;
        if (cd.kind == "Integer" || cd.kind == "Category" || xData.stats.min >= xData.stats.max) {
            minRange -= .5;
            maxRange += .5;
            this.adjustment = this.chartSize.width / (maxRange - minRange);
        }

        let xAxis = null;
        this.xScale = null;
        if (cd.kind == "Integer" ||
            cd.kind == "Double") {
            this.xScale = d3.scaleLinear()
                .domain([minRange, maxRange])
                .range([0, this.chartSize.width]);
            xAxis = d3.axisBottom(this.xScale);
        } else if (cd.kind == "Category") {
            let ticks: number[] = [];
            let labels: string[] = [];
            for (let i = 0; i < bucketCount; i++) {
                let index = i * (maxRange - minRange) / bucketCount;
                index = Math.round(index);
                ticks.push(this.adjustment / 2 + index * this.chartSize.width / (maxRange - minRange));
                labels.push(this.currentData.xData.allStrings[xData.stats.min + index]);
            }

            let axisScale = d3.scaleOrdinal()
                .domain(labels)
                .range(ticks);
            this.xScale = d3.scaleLinear()
                .domain([minRange, maxRange])
                .range([0, this.chartSize.width]);
            xAxis = d3.axisBottom(<any>axisScale);
            // cast needed probably because the d3 typings are incorrect
        } else if (cd.kind == "Date") {
            let minDate: Date = Converters.dateFromDouble(minRange);
            let maxDate: Date = Converters.dateFromDouble(maxRange);
            this.xScale = d3
                .scaleTime()
                .domain([minDate, maxDate])
                .range([0, this.chartSize.width]);
            xAxis = d3.axisBottom(this.xScale);
        }

        // force a tick on x axis for degenerate scales
        if (xData.stats.min >= xData.stats.max && xAxis != null)
            xAxis.ticks(1);

        this.canvas.append("text")
            .text(xData.description.name)
            .attr("transform", translateString(this.chartSize.width / 2,
                this.chartSize.height + Resolution.topMargin + Resolution.bottomMargin / 2))
            .attr("text-anchor", "middle");
        this.canvas.append("text")
            .text(yData.description.name)
            .attr("transform", translateString(this.chartSize.width / 2, 0))
            .attr("text-anchor", "middle")
            .attr("dominant-baseline", "hanging");

        /*
         // After resizing the line may not have the exact number of points
         // as the screen width.
         let cdfLine = d3.line<number>()
         .x((d, i) => {
         let index = Math.floor(i / 2); // two points for each data point, for a zig-zag
         return this.adjustment/2 + index * 2 * (chartWidth - this.adjustment) / cdfData.length;
         })
         .y(d => this.yScale(d));

         // draw CDF curve
         this.canvas.append("path")
         .attr("transform", translateString(
         Resolution.leftMargin, Resolution.topMargin))
         .datum(cdfData)
         .attr("stroke", "blue")
         .attr("d", cdfLine)
         .attr("fill", "none");
         */

        let barWidth = this.chartSize.width / bucketCount;
        let scale = this.chartSize.height / max;
        this.chart.selectAll("g")
        // bars
            .data(rects)
            .enter().append("g")
            .append("svg:rect")
            .attr("x", d => d.x * barWidth)
            .attr("y", d => this.rectPosition(d, counts, scale, this.chartSize.height))
            .attr("height", d => this.rectHeight(d, counts, scale, this.chartSize.height))
            .attr("width", barWidth - 1)
            .attr("fill", d => this.color(d.index, yRectangles - 1))
            .exit()
            // label bars
            .data(counts)
            .enter()
            .append("g")
            .append("text")
            .attr("class", "histogramBoxLabel")
            .attr("x", (c, i) => (i + .5) * barWidth)
            .attr("y", d => this.chartSize.height - (d * scale))
            .attr("text-anchor", "middle")
            .attr("dy", d => d <= (9 * max / 10) ? "-.25em" : ".75em")
            .text(d => (d == 0) ? "" : significantDigits(d))
            .exit();

        this.chart.append("g")
            .attr("class", "y-axis")
            .call(yAxis);
        if (xAxis != null) {
            this.chart.append("g")
                .attr("class", "x-axis")
                .attr("transform", translateString(0, this.chartSize.height))
                .call(xAxis);
        }

        let dotRadius = 3;
        this.xDot = this.canvas
            .append("circle")
            .attr("r", dotRadius)
            .attr("cy", this.chartSize.height + Resolution.topMargin)
            .attr("cx", 0)
            .attr("fill", "blue");
        this.yDot = this.canvas
            .append("circle")
            .attr("r", dotRadius)
            .attr("cx", Resolution.leftMargin)
            .attr("cy", 0)
            .attr("fill", "blue");
        this.cdfDot = this.canvas
            .append("circle")
            .attr("r", dotRadius)
            .attr("fill", "blue");

        this.legendRect = this.legendRectangle();
        let legendSvg = this.canvas
            .append("svg");

        // apparently SVG defs are global, even if they are in
        // different SVG elements.  So we have to assign unique names.
        let gradientId = 'gradient' + this.getPage().pageId;
        let gradient = legendSvg.append('defs')
            .append('linearGradient')
            .attr('id', gradientId)
            .attr('x1', '0%')
            .attr('y1', '0%')
            .attr('x2', '100%')
            .attr('y2', '0%')
            .attr('spreadMethod', 'pad');

        for (let i = 0; i <= 100; i += 4) {
            gradient.append("stop")
                .attr("offset", i + "%")
                .attr("stop-color", Histogram2DView.colorMap(i / 100))
                .attr("stop-opacity", 1)
        }

        this.legend = legendSvg.append("rect")
            .attr("width", this.legendRect.width())
            .attr("height", this.legendRect.height())
            .style("fill", "url(#" + gradientId + ")")
            .attr("x", this.legendRect.upperLeft().x)
            .attr("y", this.legendRect.upperLeft().y);

        // create a scale and axis for the legend
        this.legendScale = d3.scaleLinear()
            .domain([this.currentData.yData.stats.min, this.currentData.yData.stats.max])
            .range([0, this.legendRect.width()]);

        let legendAxis = d3.axisBottom(this.legendScale);
        legendSvg.append("g")
            .attr("transform", translateString(this.legendRect.lowerLeft().x, this.legendRect.lowerLeft().y))
            .call(legendAxis);

        this.selectionRectangle = this.canvas
            .append("rect")
            .attr("class", "dashed")
            .attr("width", 0)
            .attr("height", 0);

        let summary = formatNumber(this.currentData.visiblePoints) + " data points";
        if (missingData != 0)
            summary += ", " + formatNumber(missingData) + " missing";
        if (xData.missing.missingData != 0)
            summary += ", " + formatNumber(xData.missing.missingData) + " missing Y coordinate";
        if (yData.missing.missingData != 0)
            summary += ", " + formatNumber(yData.missing.missingData) + " missing X coordinate";
        summary += ", " + String(bucketCount) + " buckets";
        this.summary.textContent = summary;
    }

    protected legendRectangle(): Rectangle {
        let width = Resolution.legendWidth;
        if (width > this.chartSize.width)
            width = this.chartSize.width;
        let height = 15;

        let x = (this.chartSize.width - width) / 2;
        let y = Resolution.topMargin / 3;
        return new Rectangle({ x: x, y: y }, { width: width, height: height });
    }

    // We support two kinds of selection:
    // - selection of bars in the histogram area
    // - selection in the legend area
    // We distinguish the two by the original mouse position: if the mouse
    // is above the chart, we are selecting in the legend.
    protected dragStart() {
        super.dragStart();
        this.selectingLegend = this.legendRect != null && this.selectionOrigin.y < 0;
    }

    protected dragMove(): void {
        super.dragMove();
        if (!this.dragging)
            return;

        if (this.selectingLegend) {
            this.selectionRectangle
                .attr("y", this.legendRect.upperLeft().y)
                .attr("height", this.legendRect.height());
        }
    }

    // xl and xr are coordinates of the mouse position within the chart
    protected selectionCompleted(xl: number, xr: number): void {
        if (this.xScale == null)
            return;

        let min: number;
        let max: number;
        let boundaries: string[] = null;
        let selectedAxis: AxisData = null;
        let scale: AnyScale = null;

        if (this.selectingLegend) {
            // Selecting in legend.  We have to adjust xl and xr, they are relative to the chart.
            // The legend rectangle coordinates are relative to the canvas.
            let legendX = this.legendRect.lowerLeft().x;
            xl -= legendX - Resolution.leftMargin;
            xr -= legendX - Resolution.leftMargin;
            selectedAxis = this.currentData.yData;
            scale = this.legendScale;
        } else {
            selectedAxis = this.currentData.xData;
            scale = this.xScale;
        }

        let kind = selectedAxis.description.kind;
        let x0 = HistogramViewBase.invertToNumber(xl, scale, kind);
        let x1 = HistogramViewBase.invertToNumber(xr, scale, kind);

        // selection could be done in reverse
        [min, max] = reorder(x0, x1);
        if (min > max) {
            this.page.reportError("No data selected");
            return;
        }

        if (selectedAxis.allStrings != null) {
            // it's enough to just send the first and last element for filtering.
            boundaries = [selectedAxis.allStrings[Math.ceil(min)]];
            if (Math.floor(max) != Math.ceil(min))
                  boundaries.push(selectedAxis.allStrings[Math.floor(max)]);
        }

        let columnName = selectedAxis.description.name;
        let filter: FilterDescription = {
            min: min,
            max: max,
            columnName: columnName,
            bucketBoundaries: boundaries,
            complement: d3.event.sourceEvent.ctrlKey
        };

        let rr = this.createFilterRequest(filter);
        let renderer = new Histogram2DFilterReceiver(
            this.currentData.xData.description,
            this.currentData.yData.description, this.tableSchema,
            this.page, rr);
        rr.invoke(renderer);
    }

    protected rectHeight(d: Rect, counts: number[], scale: number, chartHeight: number): number {
        if (this.normalized) {
            let c = counts[d.x];
            if (c <= 0)
                return 0;
            return chartHeight * d.height / c;
        }
        return d.height * scale;
    }

    protected rectPosition(d: Rect, counts: number[], scale: number, chartHeight: number): number {
        let y = d.y + d.height;
        if (this.normalized) {
            let c = counts[d.x];
            if (c <= 0)
                return 0;
            return chartHeight * (1 - y / c);
        }
        return chartHeight - y * scale;
    }

    static colorMap(d: number): string {
        // The rainbow color map starts and ends with a similar hue
        // so we skip the first 20% of it.
        return d3.interpolateRainbow(d * .8 + .2);
    }

    color(d: number, max: number): string {
        return Histogram2DView.colorMap(d / max);
    }

    // show the table corresponding to the data in the histogram
    protected showTable(): void {
        let table = new TableView(this.remoteObjectId, this.page);
        table.setSchema(this.tableSchema);

        let order =  new RecordOrder([ {
            columnDescription: this.currentData.xData.description,
            isAscending: true
        }, {
            columnDescription: this.currentData.yData.description,
            isAscending: true
        } ]);
        let rr = table.createNextKRequest(order, null);
        let page = new FullPage();
        page.setDataView(table);
        this.page.insertAfterMe(page);
        rr.invoke(new TableRenderer(page, table, rr, false, order));
    }
}

class Histogram2DFilterReceiver extends RemoteTableRenderer {
    constructor(protected xColumn: ColumnDescription,
                protected yColumn: ColumnDescription,
                protected tableSchema: Schema,
                page: FullPage,
                operation: ICancellable) {
        super(page, operation, "Filter");
    }

    public onCompleted(): void {
        this.finished();
        if (this.remoteObject == null)
            return;

        let cds: ColumnDescription[] = [this.xColumn, this.yColumn];
        let rr = this.remoteObject.createRange2DColsRequest(this.xColumn.name, this.yColumn.name);
        rr.invoke(new Range2DCollector(cds, this.tableSchema, this.page, this.remoteObject, rr, false));
    }
}

// This class is invoked by the ZipReceiver after a set operation to create a new histogram
export class Make2DHistogram extends RemoteTableRenderer {
    public constructor(page: FullPage,
                       operation: ICancellable,
                       private colDesc: ColumnDescription[],
                       private schema: Schema,
                       private heatMap: boolean) {
        super(page, operation, "Reload");
    }

    onCompleted(): void {
        super.finished();
        if (this.remoteObject == null)
            return;
        let rr = this.remoteObject.createRange2DColsRequest(this.colDesc[0].name, this.colDesc[1].name);
        rr.setStartTime(this.operation.startTime());
        rr.invoke(new Range2DCollector(
            this.colDesc, this.schema, this.page, this.remoteObject, rr, this.heatMap));
    }
}

export class Histogram2DRenderer extends Renderer<HeatMapData> {
    protected histogram: Histogram2DView;

    constructor(page: FullPage,
                remoteTableId: string,
                protected schema: Schema,
                protected cds: ColumnDescription[],
                protected stats: BasicColStats[],
                operation: ICancellable) {
        super(new FullPage(), operation, "histogram");
        page.insertAfterMe(this.page);
        this.histogram = new Histogram2DView(remoteTableId, schema, this.page);
        this.page.setDataView(this.histogram);
        if (cds.length != 2)
            throw "Expected 2 columns, got " + cds.length;
    }

    onNext(value: PartialResult<HeatMapData>): void {
        super.onNext(value);
        if (value == null)
            return;
        let xAxisData = new AxisData(value.data.histogramMissingD1, this.cds[0], this.stats[0], null);
        let yAxisData = new AxisData(value.data.histogramMissingD2, this.cds[1], this.stats[1], null);
        this.histogram.updateView(value.data.buckets, xAxisData, yAxisData,
            value.data.missingData, this.elapsedMilliseconds());
        this.histogram.scrollIntoView();
    }
}