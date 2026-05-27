/**
 * 图表工具库 - Canvas原生绘制
 * 
 * 功能:
 * - LineChart: 实时折线图 (支持多条线、自动缩放、数据点标注、图例)
 * - BarChart: 柱状图 (支持分组、堆叠)
 * - PieChart: 饼图/环形图 (支持标签、百分比)
 * - GaugeChart: 仪表盘图 (用于单个指标、支持阈值标记)
 * - 所有图表支持动画过渡
 * 
 * @module charts
 */

// ===== 通用主题配置 =====
const ChartTheme = {
    colors: [
        '#3498db', '#2ecc71', '#e74c3c', '#f39c12', '#9b59b6',
        '#1abc9c', '#e67e22', '#34495e', '#16a085', '#c0392b',
        '#2980b9', '#27ae60', '#d35400', '#8e44ad', '#2c3e50',
    ],
    backgroundColor: 'rgba(0, 0, 0, 0)',
    gridColor: 'rgba(255, 255, 255, 0.08)',
    textColor: '#b0b0b0',
    axisColor: 'rgba(255, 255, 255, 0.2)',
    fontFamily: "'Inter', 'Segoe UI', sans-serif",
    fontSize: 11,
    padding: { top: 30, right: 20, bottom: 40, left: 60 },
    animationDuration: 600,
};

/**
 * 基础图表类 - 所有图表继承此类
 */
class BaseChart {
    /**
     * @param {string} canvasId - Canvas元素ID
     * @param {Object} options - 图表配置
     */
    constructor(canvasId, options = {}) {
        this.canvas = document.getElementById(canvasId);
        if (!this.canvas) {
            throw new Error(`Canvas元素 "${canvasId}" 不存在`);
        }
        
        this.ctx = this.canvas.getContext('2d');
        this.options = { ...ChartTheme, ...options };
        this.animationFrame = null;
        this.animationProgress = 0;
        this.animationStartTime = 0;
        this.previousData = null;
        this.currentData = null;
        
        // 设置高DPI支持
        this.setupHighDPI();
        
        // 监听窗口大小变化
        this.resizeObserver = new ResizeObserver(() => this.resize());
        this.resizeObserver.observe(this.canvas.parentElement);
    }

    /**
     * 设置高DPI显示支持
     */
    setupHighDPI() {
        const dpr = window.devicePixelRatio || 1;
        const rect = this.canvas.getBoundingClientRect();
        
        this.canvas.width = rect.width * dpr;
        this.canvas.height = rect.height * dpr;
        this.ctx.scale(dpr, dpr);
        
        this.canvas.style.width = rect.width + 'px';
        this.canvas.style.height = rect.height + 'px';
    }

    /**
     * 处理窗口大小变化
     */
    resize() {
        this.setupHighDPI();
        this.draw(1);
    }

    /**
     * 启动动画过渡
     * @param {Object} newData - 新数据
     */
    animate(newData) {
        this.previousData = this.currentData;
        this.currentData = newData;
        this.animationProgress = 0;
        this.animationStartTime = performance.now();
        
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
        }
        
        const animateFrame = (currentTime) => {
            const elapsed = currentTime - this.animationStartTime;
            this.animationProgress = Math.min(elapsed / this.options.animationDuration, 1);
            
            // 使用缓动函数
            const eased = this.easeInOutCubic(this.animationProgress);
            this.draw(eased);
            
            if (this.animationProgress < 1) {
                this.animationFrame = requestAnimationFrame(animateFrame);
            }
        };
        
        this.animationFrame = requestAnimationFrame(animateFrame);
    }

    /**
     * 缓动函数 - 三次贝塞尔
     * @param {number} t - 进度 [0,1]
     * @returns {number} 缓动值
     */
    easeInOutCubic(t) {
        return t < 0.5
            ? 4 * t * t * t
            : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /**
     * 插值
     * @param {number} from - 起始值
     * @param {number} to - 目标值
     * @param {number} progress - 进度
     * @returns {number} 插值结果
     */
    lerp(from, to, progress) {
        if (from === undefined || from === null) return to;
        return from + (to - from) * progress;
    }

    /**
     * 绘制 (子类实现)
     * @param {number} progress - 动画进度 [0,1]
     */
    draw(progress = 1) {
        throw new Error('子类必须实现 draw 方法');
    }

    /**
     * 清除画布
     */
    clear() {
        const rect = this.canvas.getBoundingClientRect();
        this.ctx.clearRect(0, 0, rect.width, rect.height);
    }

    /**
     * 销毁图表
     */
    destroy() {
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
        }
        this.resizeObserver?.disconnect();
    }
}

/**
 * 折线图类
 * 支持多条线、自动缩放、数据点标注、图例
 */
class LineChart extends BaseChart {
    /**
     * @param {string} canvasId - Canvas元素ID
     * @param {Object} options - 配置项
     */
    constructor(canvasId, options = {}) {
        super(canvasId, options);
        
        this.lines = [];           // 线数据 [{name, data: [{x,y}...], color}]
        this.showLegend = options.showLegend !== false;
        this.showDots = options.showDots !== false;
        this.showGrid = options.showGrid !== false;
        this.autoScale = options.autoScale !== false;
        this.yMin = options.yMin;
        this.yMax = options.yMax;
        this.maxPoints = options.maxPoints || 100;
    }

    /**
     * 设置线数据
     * @param {Array} lines - [{name, data: [{x, y}], color}]
     */
    setData(lines) {
        this.lines = lines.map((line, i) => ({
            ...line,
            color: line.color || ChartTheme.colors[i % ChartTheme.colors.length],
        }));
        this.animate(lines);
    }

    /**
     * 添加数据点到指定线
     * @param {number} lineIndex - 线索引
     * @param {Object} point - {x, y}
     */
    addPoint(lineIndex, point) {
        if (!this.lines[lineIndex]) return;
        
        this.lines[lineIndex].data.push(point);
        if (this.lines[lineIndex].data.length > this.maxPoints) {
            this.lines[lineIndex].data.shift();
        }
        
        this.animate(this.lines);
    }

    /**
     * 绘制折线图
     * @param {number} progress - 动画进度
     */
    draw(progress = 1) {
        this.clear();
        
        if (this.lines.length === 0) return;
        
        const rect = this.canvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        const p = this.options.padding;
        const chartW = w - p.left - p.right;
        const chartH = h - p.top - p.bottom;
        
        // 计算数据范围
        let allY = [];
        this.lines.forEach(line => {
            line.data.forEach(pt => allY.push(pt.y));
        });
        
        if (allY.length === 0) return;
        
        let yMin = this.yMin !== undefined ? this.yMin : Math.min(...allY);
        let yMax = this.yMax !== undefined ? this.yMax : Math.max(...allY);
        
        if (yMin === yMax) {
            yMin -= 1;
            yMax += 1;
        }
        
        // 添加上下边距
        const yRange = yMax - yMin;
        yMax += yRange * 0.1;
        yMin -= yRange * 0.1;
        
        // 计算最大点数（用于确定x范围）
        const maxDataLen = Math.max(...this.lines.map(l => l.data.length));
        const xMin = 0;
        const xMax = Math.max(maxDataLen - 1, 1);
        
        /**
         * 将数据坐标转换为画布坐标
         */
        const toCanvasX = (index) => p.left + (index / xMax) * chartW;
        const toCanvasY = (value) => p.top + chartH - ((value - yMin) / (yMax - yMin)) * chartH;
        
        // 绘制网格
        if (this.showGrid) {
            this.drawGrid(w, h, p, chartW, chartH, yMin, yMax);
        }
        
        // 绘制每条线
        this.lines.forEach((line, lineIdx) => {
            if (line.data.length === 0) return;
            
            this.ctx.strokeStyle = line.color;
            this.ctx.lineWidth = 2;
            this.ctx.lineJoin = 'round';
            this.ctx.lineCap = 'round';
            
            // 绘制线
            this.ctx.beginPath();
            line.data.forEach((pt, i) => {
                const x = toCanvasX(i);
                const y = toCanvasY(pt.y);
                
                if (i === 0) {
                    this.ctx.moveTo(x, y);
                } else {
                    this.ctx.lineTo(x, y);
                }
            });
            this.ctx.stroke();
            
            // 绘制渐变填充
            const gradient = this.ctx.createLinearGradient(0, p.top, 0, p.top + chartH);
            gradient.addColorStop(0, line.color + '40');
            gradient.addColorStop(1, line.color + '05');
            
            this.ctx.fillStyle = gradient;
            this.ctx.beginPath();
            line.data.forEach((pt, i) => {
                const x = toCanvasX(i);
                const y = toCanvasY(pt.y);
                if (i === 0) this.ctx.moveTo(x, y);
                else this.ctx.lineTo(x, y);
            });
            this.ctx.lineTo(toCanvasX(line.data.length - 1), p.top + chartH);
            this.ctx.lineTo(toCanvasX(0), p.top + chartH);
            this.ctx.closePath();
            this.ctx.fill();
            
            // 绘制数据点
            if (this.showDots && line.data.length <= 30) {
                line.data.forEach((pt, i) => {
                    const x = toCanvasX(i);
                    const y = toCanvasY(pt.y);
                    
                    this.ctx.beginPath();
                    this.ctx.arc(x, y, 3, 0, Math.PI * 2);
                    this.ctx.fillStyle = line.color;
                    this.ctx.fill();
                    
                    this.ctx.beginPath();
                    this.ctx.arc(x, y, 1.5, 0, Math.PI * 2);
                    this.ctx.fillStyle = '#fff';
                    this.ctx.fill();
                });
            }
        });
        
        // 绘制图例
        if (this.showLegend) {
            this.drawLegend(w, p);
        }
    }

    /**
     * 绘制网格
     */
    drawGrid(w, h, p, chartW, chartH, yMin, yMax) {
        this.ctx.strokeStyle = this.options.gridColor;
        this.ctx.lineWidth = 1;
        this.ctx.fillStyle = this.options.textColor;
        this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
        this.ctx.textAlign = 'right';
        
        // Y轴网格线
        const yTicks = 5;
        for (let i = 0; i <= yTicks; i++) {
            const y = p.top + (chartH / yTicks) * i;
            const value = yMax - ((yMax - yMin) / yTicks) * i;
            
            this.ctx.beginPath();
            this.ctx.moveTo(p.left, y);
            this.ctx.lineTo(p.left + chartW, y);
            this.ctx.stroke();
            
            // Y轴标签
            const label = value >= 1000 
                ? (value / 1000).toFixed(1) + 'k' 
                : value >= 1 
                    ? value.toFixed(1) 
                    : value.toFixed(3);
            this.ctx.fillText(label, p.left - 8, y + 4);
        }
        
        // X轴轴线
        this.ctx.strokeStyle = this.options.axisColor;
        this.ctx.beginPath();
        this.ctx.moveTo(p.left, p.top + chartH);
        this.ctx.lineTo(p.left + chartW, p.top + chartH);
        this.ctx.stroke();
        
        // Y轴轴线
        this.ctx.beginPath();
        this.ctx.moveTo(p.left, p.top);
        this.ctx.lineTo(p.left, p.top + chartH);
        this.ctx.stroke();
    }

    /**
     * 绘制图例
     */
    drawLegend(w, p) {
        const legendY = 12;
        let legendX = p.left;
        
        this.lines.forEach((line, i) => {
            // 色块
            this.ctx.fillStyle = line.color;
            this.ctx.fillRect(legendX, legendY, 12, 12);
            
            // 文字
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'left';
            this.ctx.fillText(line.name || `Line ${i + 1}`, legendX + 16, legendY + 10);
            
            legendX += this.ctx.measureText(line.name || `Line ${i + 1}`).width + 30;
        });
    }
}

/**
 * 柱状图类
 * 支持分组柱状图、堆叠柱状图
 */
class BarChart extends BaseChart {
    /**
     * @param {string} canvasId - Canvas元素ID
     * @param {Object} options - 配置项
     */
    constructor(canvasId, options = {}) {
        super(canvasId, options);
        
        this.barData = [];         // [{label, values: [num, ...]}]
        this.seriesNames = [];     // 系列名称
        this.seriesColors = [];    // 系列颜色
        this.stacked = options.stacked || false;
        this.barGap = options.barGap || 0.3;
        this.showLabels = options.showLabels !== false;
    }

    /**
     * 设置柱状图数据
     * @param {Object} data - {labels: [], series: [{name, values: [], color}]}
     */
    setData(data) {
        this.seriesNames = data.series.map(s => s.name);
        this.seriesColors = data.series.map((s, i) => s.color || ChartTheme.colors[i]);
        
        this.barData = data.labels.map((label, i) => ({
            label,
            values: data.series.map(s => s.values[i] || 0),
        }));
        
        this.animate(this.barData);
    }

    /**
     * 绘制柱状图
     * @param {number} progress - 动画进度
     */
    draw(progress = 1) {
        this.clear();
        
        if (this.barData.length === 0) return;
        
        const rect = this.canvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        const p = this.options.padding;
        const chartW = w - p.left - p.right;
        const chartH = h - p.top - p.bottom;
        
        // 计算范围
        let maxVal;
        if (this.stacked) {
            maxVal = Math.max(...this.barData.map(d => d.values.reduce((a, b) => a + b, 0)));
        } else {
            maxVal = Math.max(...this.barData.flatMap(d => d.values));
        }
        maxVal = maxVal * 1.1 || 1;
        
        const barGroupWidth = chartW / this.barData.length;
        const barWidth = barGroupWidth * (1 - this.barGap) / (this.stacked ? 1 : this.seriesNames.length);
        const barGapPx = barGroupWidth * this.barGap / 2;
        
        // 绘制网格
        this.drawGrid(w, h, p, chartW, chartH, maxVal);
        
        // 绘制柱子
        this.barData.forEach((group, groupIdx) => {
            const groupX = p.left + groupIdx * barGroupWidth;
            
            if (this.stacked) {
                // 堆叠模式
                let stackY = 0;
                group.values.forEach((val, seriesIdx) => {
                    const barH = (val / maxVal) * chartH * progress;
                    const x = groupX + barGapPx;
                    const y = p.top + chartH - stackY - barH;
                    
                    this.ctx.fillStyle = this.seriesColors[seriesIdx];
                    this.ctx.beginPath();
                    this.roundRect(x, y, barWidth, barH, 3);
                    this.ctx.fill();
                    
                    stackY += barH;
                });
            } else {
                // 分组模式
                group.values.forEach((val, seriesIdx) => {
                    const barH = (val / maxVal) * chartH * progress;
                    const x = groupX + barGapPx + seriesIdx * barWidth;
                    const y = p.top + chartH - barH;
                    
                    this.ctx.fillStyle = this.seriesColors[seriesIdx];
                    this.ctx.beginPath();
                    this.roundRect(x, y, barWidth - 2, barH, 3);
                    this.ctx.fill();
                    
                    // 数值标签
                    if (this.showLabels && barH > 15) {
                        this.ctx.fillStyle = '#fff';
                        this.ctx.font = `${this.options.fontSize - 1}px ${this.options.fontFamily}`;
                        this.ctx.textAlign = 'center';
                        this.ctx.fillText(
                            val >= 1000 ? (val / 1000).toFixed(1) + 'k' : val.toFixed(0),
                            x + (barWidth - 2) / 2,
                            y + 14
                        );
                    }
                });
            }
            
            // X轴标签
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'center';
            this.ctx.fillText(
                group.label,
                groupX + barGroupWidth / 2,
                p.top + chartH + 20
            );
        });
        
        // 图例
        this.drawLegend(w, p);
    }

    /**
     * 绘制网格
     */
    drawGrid(w, h, p, chartW, chartH, maxVal) {
        this.ctx.strokeStyle = this.options.gridColor;
        this.ctx.lineWidth = 1;
        this.ctx.fillStyle = this.options.textColor;
        this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
        this.ctx.textAlign = 'right';
        
        const ticks = 4;
        for (let i = 0; i <= ticks; i++) {
            const y = p.top + (chartH / ticks) * i;
            const value = maxVal - (maxVal / ticks) * i;
            
            this.ctx.beginPath();
            this.ctx.moveTo(p.left, y);
            this.ctx.lineTo(p.left + chartW, y);
            this.ctx.stroke();
            
            const label = value >= 1000 
                ? (value / 1000).toFixed(1) + 'k' 
                : value.toFixed(0);
            this.ctx.fillText(label, p.left - 8, y + 4);
        }
        
        // 轴线
        this.ctx.strokeStyle = this.options.axisColor;
        this.ctx.beginPath();
        this.ctx.moveTo(p.left, p.top + chartH);
        this.ctx.lineTo(p.left + chartW, p.top + chartH);
        this.ctx.stroke();
    }

    /**
     * 绘制圆角矩形
     */
    roundRect(x, y, w, h, r) {
        if (h < 0) {
            y += h;
            h = Math.abs(h);
        }
        r = Math.min(r, w / 2, h / 2);
        this.ctx.moveTo(x + r, y);
        this.ctx.arcTo(x + w, y, x + w, y + h, r);
        this.ctx.arcTo(x + w, y + h, x, y + h, r);
        this.ctx.arcTo(x, y + h, x, y, r);
        this.ctx.arcTo(x, y, x + w, y, r);
        this.ctx.closePath();
    }

    /**
     * 绘制图例
     */
    drawLegend(w, p) {
        const legendY = 12;
        let legendX = p.left;
        
        this.seriesNames.forEach((name, i) => {
            this.ctx.fillStyle = this.seriesColors[i];
            this.ctx.fillRect(legendX, legendY, 12, 12);
            
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'left';
            this.ctx.fillText(name, legendX + 16, legendY + 10);
            
            legendX += this.ctx.measureText(name).width + 30;
        });
    }
}

/**
 * 饼图/环形图类
 * 支持标签、百分比显示
 */
class PieChart extends BaseChart {
    /**
     * @param {string} canvasId - Canvas元素ID
     * @param {Object} options - 配置项
     */
    constructor(canvasId, options = {}) {
        super(canvasId, options);
        
        this.pieData = [];       // [{label, value, color}]
        this.isDonut = options.isDonut || false;
        this.innerRadius = options.innerRadius || 0.6;
        this.showLabels = options.showLabels !== false;
        this.showPercent = options.showPercent !== false;
    }

    /**
     * 设置饼图数据
     * @param {Array} data - [{label, value, color}]
     */
    setData(data) {
        this.pieData = data.map((item, i) => ({
            ...item,
            color: item.color || ChartTheme.colors[i % ChartTheme.colors.length],
        }));
        this.animate(this.pieData);
    }

    /**
     * 绘制饼图
     * @param {number} progress - 动画进度
     */
    draw(progress = 1) {
        this.clear();
        
        if (this.pieData.length === 0) return;
        
        const rect = this.canvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        
        const total = this.pieData.reduce((sum, d) => sum + d.value, 0);
        if (total === 0) return;
        
        const cx = w / 2 - (this.showLabels ? 40 : 0);
        const cy = h / 2;
        const radius = Math.min(cx - 20, cy - 30);
        const innerR = this.isDonut ? radius * this.innerRadius : 0;
        
        let startAngle = -Math.PI / 2;
        
        this.pieData.forEach((item, i) => {
            const sliceAngle = (item.value / total) * Math.PI * 2 * progress;
            const endAngle = startAngle + sliceAngle;
            
            // 绘制扇形
            this.ctx.beginPath();
            this.ctx.moveTo(cx + innerR * Math.cos(startAngle), cy + innerR * Math.sin(startAngle));
            this.ctx.arc(cx, cy, radius, startAngle, endAngle);
            
            if (this.isDonut) {
                this.ctx.arc(cx, cy, innerR, endAngle, startAngle, true);
            } else {
                this.ctx.lineTo(cx, cy);
            }
            
            this.ctx.closePath();
            this.ctx.fillStyle = item.color;
            this.ctx.fill();
            
            // 高亮当前扇形
            if (sliceAngle > 0.3) {
                const midAngle = startAngle + sliceAngle / 2;
                const labelR = this.isDonut 
                    ? (radius + innerR) / 2 
                    : radius * 0.65;
                const lx = cx + labelR * Math.cos(midAngle);
                const ly = cy + labelR * Math.sin(midAngle);
                
                this.ctx.fillStyle = '#fff';
                this.ctx.font = `bold ${this.options.fontSize}px ${this.options.fontFamily}`;
                this.ctx.textAlign = 'center';
                this.ctx.textBaseline = 'middle';
                
                if (this.showPercent) {
                    const pct = ((item.value / total) * 100).toFixed(1);
                    this.ctx.fillText(`${pct}%`, lx, ly);
                }
            }
            
            startAngle = endAngle;
        });
        
        // 中心文字 (环形图)
        if (this.isDonut) {
            this.ctx.fillStyle = '#e0e0e0';
            this.ctx.font = `bold 20px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'center';
            this.ctx.textBaseline = 'middle';
            this.ctx.fillText(this.formatNumber(total), cx, cy - 8);
            
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
            this.ctx.fillText('Total', cx, cy + 14);
        }
        
        // 右侧图例
        if (this.showLabels) {
            this.drawLabels(w, h, total);
        }
    }

    /**
     * 绘制右侧图例标签
     */
    drawLabels(w, h, total) {
        const labelX = w - 120;
        let labelY = h / 2 - (this.pieData.length * 16) / 2;
        
        this.pieData.forEach((item) => {
            // 色块
            this.ctx.fillStyle = item.color;
            this.ctx.fillRect(labelX, labelY, 10, 10);
            
            // 文字
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'left';
            this.ctx.textBaseline = 'top';
            
            const pct = ((item.value / total) * 100).toFixed(1);
            this.ctx.fillText(`${item.label} (${pct}%)`, labelX + 14, labelY - 1);
            
            labelY += 20;
        });
    }

    /**
     * 格式化数字
     */
    formatNumber(num) {
        if (num >= 1e6) return (num / 1e6).toFixed(1) + 'M';
        if (num >= 1e3) return (num / 1e3).toFixed(1) + 'K';
        return num.toString();
    }
}

/**
 * 仪表盘图类
 * 用于显示单个指标，支持阈值标记
 */
class GaugeChart extends BaseChart {
    /**
     * @param {string} canvasId - Canvas元素ID
     * @param {Object} options - 配置项
     */
    constructor(canvasId, options = {}) {
        super(canvasId, options);
        
        this.gaugeValue = 0;         // 当前值
        this.gaugeMin = options.min || 0;
        this.gaugeMax = options.max || 100;
        this.gaugeUnit = options.unit || '';
        this.gaugeLabel = options.label || '';
        this.thresholds = options.thresholds || []; // [{value, color, label}]
        this.displayValue = 0;       // 当前显示值 (动画)
    }

    /**
     * 设置仪表盘值
     * @param {number} value - 当前值
     * @param {string} label - 标签 (可选)
     */
    setValue(value, label) {
        this.gaugeValue = value;
        if (label !== undefined) this.gaugeLabel = label;
        this.animate(this.gaugeValue);
    }

    /**
     * 设置阈值
     * @param {Array} thresholds - [{value, color, label}]
     */
    setThresholds(thresholds) {
        this.thresholds = thresholds;
        this.draw(1);
    }

    /**
     * 绘制仪表盘
     * @param {number} progress - 动画进度
     */
    draw(progress = 1) {
        this.clear();
        
        const rect = this.canvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        const cx = w / 2;
        const cy = h * 0.65;
        const radius = Math.min(cx - 10, cy - 10) * 0.85;
        
        // 计算当前显示值
        this.displayValue = this.lerp(
            this.previousData !== null ? this.previousData : this.gaugeMin,
            this.gaugeValue,
            progress
        );
        
        const valueRatio = Math.max(0, Math.min(1, 
            (this.displayValue - this.gaugeMin) / (this.gaugeMax - this.gaugeMin)
        ));
        
        // 起始角度和结束角度 (270度弧)
        const startAngle = Math.PI * 0.75;
        const endAngle = Math.PI * 2.25;
        const totalAngle = endAngle - startAngle;
        
        // 绘制背景弧
        this.ctx.beginPath();
        this.ctx.arc(cx, cy, radius, startAngle, endAngle);
        this.ctx.strokeStyle = 'rgba(255,255,255,0.08)';
        this.ctx.lineWidth = radius * 0.15;
        this.ctx.lineCap = 'round';
        this.ctx.stroke();
        
        // 绘制阈值区间
        this.thresholds.forEach((threshold, i) => {
            const prevValue = i > 0 ? this.thresholds[i - 1].value : this.gaugeMin;
            const nextValue = threshold.value;
            
            const startRatio = (prevValue - this.gaugeMin) / (this.gaugeMax - this.gaugeMin);
            const endRatio = (nextValue - this.gaugeMin) / (this.gaugeMax - this.gaugeMin);
            
            const tStart = startAngle + startRatio * totalAngle;
            const tEnd = startAngle + endRatio * totalAngle;
            
            this.ctx.beginPath();
            this.ctx.arc(cx, cy, radius, tStart, tEnd);
            this.ctx.strokeStyle = threshold.color + '30';
            this.ctx.lineWidth = radius * 0.15;
            this.ctx.lineCap = 'butt';
            this.ctx.stroke();
        });
        
        // 绘制值弧
        const valueAngle = startAngle + valueRatio * totalAngle;
        const gradient = this.ctx.createLinearGradient(cx - radius, cy, cx + radius, cy);
        
        // 根据值选择颜色
        let valueColor = this.getValueColor(valueRatio);
        gradient.addColorStop(0, valueColor);
        gradient.addColorStop(1, valueColor);
        
        this.ctx.beginPath();
        this.ctx.arc(cx, cy, radius, startAngle, valueAngle);
        this.ctx.strokeStyle = gradient;
        this.ctx.lineWidth = radius * 0.15;
        this.ctx.lineCap = 'round';
        this.ctx.stroke();
        
        // 绘制指针端点
        this.ctx.beginPath();
        this.ctx.arc(
            cx + radius * Math.cos(valueAngle),
            cy + radius * Math.sin(valueAngle),
            radius * 0.08,
            0,
            Math.PI * 2
        );
        this.ctx.fillStyle = '#fff';
        this.ctx.fill();
        
        // 中心数值
        this.ctx.fillStyle = '#e0e0e0';
        this.ctx.font = `bold ${radius * 0.35}px ${this.options.fontFamily}`;
        this.ctx.textAlign = 'center';
        this.ctx.textBaseline = 'middle';
        
        let displayText;
        if (this.gaugeMax >= 10000) {
            displayText = (this.displayValue / 1000).toFixed(1) + 'K';
        } else if (this.gaugeMax < 1) {
            displayText = (this.displayValue * 100).toFixed(1) + '%';
        } else {
            displayText = Math.round(this.displayValue).toString();
        }
        
        this.ctx.fillText(displayText, cx, cy - 5);
        
        // 单位
        if (this.gaugeUnit) {
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${radius * 0.12}px ${this.options.fontFamily}`;
            this.ctx.fillText(this.gaugeUnit, cx, cy + radius * 0.18);
        }
        
        // 标签
        if (this.gaugeLabel) {
            this.ctx.fillStyle = this.options.textColor;
            this.ctx.font = `${radius * 0.14}px ${this.options.fontFamily}`;
            this.ctx.textAlign = 'center';
            this.ctx.fillText(this.gaugeLabel, cx, cy + radius * 0.45);
        }
        
        // 最小/最大刻度
        this.ctx.fillStyle = this.options.textColor;
        this.ctx.font = `${this.options.fontSize}px ${this.options.fontFamily}`;
        this.ctx.textAlign = 'center';
        
        const minX = cx + (radius + 15) * Math.cos(startAngle);
        const minY = cy + (radius + 15) * Math.sin(startAngle);
        this.ctx.fillText(this.formatValue(this.gaugeMin), minX, minY);
        
        const maxX = cx + (radius + 15) * Math.cos(endAngle);
        const maxY = cy + (radius + 15) * Math.sin(endAngle);
        this.ctx.fillText(this.formatValue(this.gaugeMax), maxX, maxY);
        
        // 阈值标记
        this.thresholds.forEach(threshold => {
            const tRatio = (threshold.value - this.gaugeMin) / (this.gaugeMax - this.gaugeMin);
            const tAngle = startAngle + tRatio * totalAngle;
            const tx = cx + (radius + 8) * Math.cos(tAngle);
            const ty = cy + (radius + 8) * Math.sin(tAngle);
            
            this.ctx.beginPath();
            this.ctx.arc(tx, ty, 3, 0, Math.PI * 2);
            this.ctx.fillStyle = threshold.color;
            this.ctx.fill();
        });
    }

    /**
     * 根据值比例获取颜色
     */
    getValueColor(ratio) {
        if (ratio < 0.5) return '#2ecc71';      // 绿色
        if (ratio < 0.75) return '#f39c12';     // 橙色
        return '#e74c3c';                        // 红色
    }

    /**
     * 格式化刻度值
     */
    formatValue(value) {
        if (this.gaugeMax >= 10000) return (value / 1000).toFixed(0) + 'K';
        if (this.gaugeMax < 1) return (value * 100).toFixed(0) + '%';
        return value.toString();
    }
}

// ===== 导出到全局 =====
window.LineChart = LineChart;
window.BarChart = BarChart;
window.PieChart = PieChart;
window.GaugeChart = GaugeChart;
window.ChartTheme = ChartTheme;
