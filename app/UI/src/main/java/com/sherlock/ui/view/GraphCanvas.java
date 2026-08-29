package com.sherlock.ui.view;

import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.*;
import java.util.function.Consumer;

public class GraphCanvas extends StackPane {

    private final Canvas canvas = new Canvas(400, 300);
    private final Canvas minimapCanvas = new Canvas(140, 90);
    private final List<NodeDto> nodes = new ArrayList<>();
    private final List<EdgeDto> edges = new ArrayList<>();
    private final Map<String, NodeDto> nodeLookup = new HashMap<>();

    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private double dragStartX, dragStartY;
    private NodeDto draggedNode = null;
    private NodeDto selectedNode = null;
    private EdgeDto selectedEdge = null;
    private NodeDto hoveredNode = null;
    private EdgeDto hoveredEdge = null;

    private boolean isPanMode = false;
    private boolean isSimulating = true;
    private int simulationTicks = 0;

    private Consumer<NodeDto> onNodeSelected;
    private Consumer<EdgeDto> onEdgeSelected;
    private Runnable onSelectionCleared;

    private final AnimationTimer renderLoop;

    public GraphCanvas() {
        setStyle("-fx-background-color: #0b0e14; -fx-background-radius: 10px; -fx-border-color: #1e293b; -fx-border-radius: 10px; -fx-border-width: 1px;");
        setMinSize(300, 200);

        // Bind canvas dimensions to StackPane dimensions
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldVal, newVal) -> render());
        heightProperty().addListener((obs, oldVal, newVal) -> render());

        // Top-right toolbar
        HBox toolbar = buildToolbar();
        StackPane.setAlignment(toolbar, Pos.TOP_RIGHT);
        StackPane.setMargin(toolbar, new Insets(14));

        // Bottom-left Minimap
        VBox minimapBox = buildMinimap();
        StackPane.setAlignment(minimapBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(minimapBox, new Insets(14));

        // Bottom-right Legend
        HBox legendBox = buildLegend();
        StackPane.setAlignment(legendBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legendBox, new Insets(8));

        getChildren().addAll(canvas, toolbar, minimapBox, legendBox);

        setupMouseHandlers();

        // Physics & Animation Loop
        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (isSimulating && simulationTicks < 300 && !nodes.isEmpty()) {
                    stepPhysics();
                    simulationTicks++;
                }
                render();
            }
        };
        renderLoop.start();
    }

    public void setGraphData(GraphDataDto data) {
        nodes.clear();
        edges.clear();
        nodeLookup.clear();
        selectedNode = null;
        selectedEdge = null;
        hoveredNode = null;
        hoveredEdge = null;

        if (data != null) {
            if (data.getNodes() != null) {
                nodes.addAll(data.getNodes());
            }
            if (data.getEdges() != null) {
                edges.addAll(data.getEdges());
            }
        }

        if (nodes.isEmpty()) {
            zoom = 1.0;
            panX = 0;
            panY = 0;
            isSimulating = false;
            render();
            return;
        }

        // Initialize positions in a circle / radial layout
        double cx = getWidth() > 0 ? getWidth() / 2 : 400;
        double cy = getHeight() > 0 ? getHeight() / 2 : 300;
        double radius = Math.max(120, Math.min(cx, cy) * 0.65);

        for (int i = 0; i < nodes.size(); i++) {
            NodeDto n = nodes.get(i);
            double angle = (2 * Math.PI * i) / Math.max(1, nodes.size());
            n.setX(cx + radius * Math.cos(angle) + (Math.random() - 0.5) * 40);
            n.setY(cy + radius * Math.sin(angle) + (Math.random() - 0.5) * 40);
            n.setVx(0);
            n.setVy(0);

            if (n.getId() != null) nodeLookup.put(n.getId().toLowerCase(Locale.ROOT), n);
            if (n.getName() != null) nodeLookup.put(n.getName().toLowerCase(Locale.ROOT), n);
        }

        // Reset view
        zoom = 1.0;
        panX = 0;
        panY = 0;
        simulationTicks = 0;
        isSimulating = true;

        fitToView();
    }

    private HBox buildToolbar() {
        HBox toolbar = new HBox(6);
        toolbar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setStyle("-fx-background-color: rgba(18, 24, 36, 0.85); -fx-padding: 6px; -fx-background-radius: 8px; -fx-border-color: #243048; -fx-border-radius: 8px;");

        Button selectBtn = new Button("↖");
        selectBtn.setTooltip(new Tooltip("Select Tool"));
        selectBtn.getStyleClass().add("tool-button-active");

        Button panBtn = new Button("✋");
        panBtn.setTooltip(new Tooltip("Pan Tool"));
        panBtn.getStyleClass().add("tool-button");

        selectBtn.setOnAction(e -> {
            isPanMode = false;
            selectBtn.getStyleClass().setAll("tool-button-active");
            panBtn.getStyleClass().setAll("tool-button");
        });

        panBtn.setOnAction(e -> {
            isPanMode = true;
            panBtn.getStyleClass().setAll("tool-button-active");
            selectBtn.getStyleClass().setAll("tool-button");
        });

        Button zoomInBtn = new Button("＋");
        zoomInBtn.setTooltip(new Tooltip("Zoom In"));
        zoomInBtn.getStyleClass().add("tool-button");
        zoomInBtn.setOnAction(e -> {
            zoom = Math.min(3.0, zoom * 1.2);
            render();
        });

        Button zoomOutBtn = new Button("－");
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out"));
        zoomOutBtn.getStyleClass().add("tool-button");
        zoomOutBtn.setOnAction(e -> {
            zoom = Math.max(0.2, zoom / 1.2);
            render();
        });

        Button fitBtn = new Button("⛶");
        fitBtn.setTooltip(new Tooltip("Fit Graph to Screen"));
        fitBtn.getStyleClass().add("tool-button");
        fitBtn.setOnAction(e -> fitToView());

        Button resetBtn = new Button("↺");
        resetBtn.setTooltip(new Tooltip("Re-simulate / Reset"));
        resetBtn.getStyleClass().add("tool-button");
        resetBtn.setOnAction(e -> {
            simulationTicks = 0;
            isSimulating = true;
        });

        toolbar.getChildren().addAll(selectBtn, panBtn, zoomInBtn, zoomOutBtn, fitBtn, resetBtn);
        return toolbar;
    }

    private VBox buildMinimap() {
        VBox box = new VBox(4);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setStyle("-fx-background-color: rgba(18, 24, 36, 0.85); -fx-padding: 6px; -fx-background-radius: 8px; -fx-border-color: #243048; -fx-border-radius: 8px;");

        Label title = new Label("Minimap");
        title.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8; -fx-font-weight: bold;");

        minimapCanvas.setWidth(130);
        minimapCanvas.setHeight(85);

        box.getChildren().addAll(title, minimapCanvas);
        return box;
    }

    private HBox buildLegend() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setStyle("-fx-background-color: rgba(15, 23, 42, 0.85); -fx-padding: 2px 4px; -fx-background-radius: 4px; -fx-border-color: #1e293b; -fx-border-radius: 4px; -fx-border-width: 1px;");

        Label title = new Label("LEGEND:");
        title.setStyle("-fx-font-size: 7px; -fx-text-fill: #64748b; -fx-font-weight: bold;");
        box.getChildren().add(title);

        box.getChildren().add(legendItem(Color.web("#3B82F6"), "Person"));
        box.getChildren().add(legendItem(Color.web("#06B6D4"), "Phone"));
        box.getChildren().add(legendItem(Color.web("#10B981"), "Doc"));
        box.getChildren().add(legendItem(Color.web("#F59E0B"), "Location"));
        box.getChildren().add(legendItem(Color.web("#F43F5E"), "Event"));
        box.getChildren().add(legendItem(Color.web("#8B5CF6"), "Org"));

        return box;
    }

    private HBox legendItem(Color color, String text) {
        HBox row = new HBox(2);
        row.setAlignment(Pos.CENTER_LEFT);
        Circle dot = new Circle(2, color);
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 7px; -fx-text-fill: #94a3b8;");
        row.getChildren().addAll(dot, label);
        return row;
    }

    private void setupMouseHandlers() {
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            dragStartX = e.getX();
            dragStartY = e.getY();

            if (e.getButton() == MouseButton.PRIMARY && !isPanMode) {
                NodeDto clickedNode = findNodeAt(e.getX(), e.getY());
                if (clickedNode != null) {
                    draggedNode = clickedNode;
                    selectedNode = clickedNode;
                    selectedEdge = null;
                    if (onNodeSelected != null) onNodeSelected.accept(clickedNode);
                    render();
                    return;
                }

                EdgeDto clickedEdge = findEdgeAt(e.getX(), e.getY());
                if (clickedEdge != null) {
                    selectedEdge = clickedEdge;
                    selectedNode = null;
                    if (onEdgeSelected != null) onEdgeSelected.accept(clickedEdge);
                    render();
                    return;
                }

                selectedNode = null;
                selectedEdge = null;
                if (onSelectionCleared != null) onSelectionCleared.run();
                render();
            }
        });

        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            double dx = e.getX() - dragStartX;
            double dy = e.getY() - dragStartY;

            if (draggedNode != null) {
                draggedNode.setX(draggedNode.getX() + dx / zoom);
                draggedNode.setY(draggedNode.getY() + dy / zoom);
                draggedNode.setVx(0);
                draggedNode.setVy(0);
                dragStartX = e.getX();
                dragStartY = e.getY();
                render();
            } else {
                panX += dx;
                panY += dy;
                dragStartX = e.getX();
                dragStartY = e.getY();
                render();
            }
        });

        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            draggedNode = null;
        });

        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            NodeDto oldHoverNode = hoveredNode;
            EdgeDto oldHoverEdge = hoveredEdge;

            hoveredNode = findNodeAt(e.getX(), e.getY());
            hoveredEdge = hoveredNode == null ? findEdgeAt(e.getX(), e.getY()) : null;

            if (hoveredNode != oldHoverNode || hoveredEdge != oldHoverEdge) {
                render();
            }
        });

        canvas.addEventHandler(ScrollEvent.SCROLL, (ScrollEvent e) -> {
            double mouseX = e.getX();
            double mouseY = e.getY();
            double zoomFactor = e.getDeltaY() > 0 ? 1.12 : 1 / 1.12;

            double newZoom = Math.max(0.2, Math.min(3.5, zoom * zoomFactor));
            if (newZoom != zoom) {
                panX = mouseX - (mouseX - panX) * (newZoom / zoom);
                panY = mouseY - (mouseY - panY) * (newZoom / zoom);
                zoom = newZoom;
                render();
            }
            e.consume();
        });
    }

    public void fitToView() {
        if (nodes.isEmpty()) {
            zoom = 1.0;
            panX = 0;
            panY = 0;
            render();
            return;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (NodeDto n : nodes) {
            minX = Math.min(minX, n.getX());
            maxX = Math.max(maxX, n.getX());
            minY = Math.min(minY, n.getY());
            maxY = Math.max(maxY, n.getY());
        }

        double graphW = Math.max(120, maxX - minX + 160);
        double graphH = Math.max(120, maxY - minY + 160);

        double canvasW = Math.max(120, canvas.getWidth());
        double canvasH = Math.max(120, canvas.getHeight());

        zoom = Math.min(1.2, Math.min(canvasW / graphW, canvasH / graphH) * 0.85);
        if (zoom < 0.3) zoom = 0.3;

        double graphCenterX = (minX + maxX) / 2;
        double graphCenterY = (minY + maxY) / 2;

        panX = (canvasW / 2) - graphCenterX * zoom;
        panY = (canvasH / 2) - graphCenterY * zoom;

        render();
    }

    private void stepPhysics() {
        if (nodes.isEmpty()) return;

        double repulsion = 4500.0;
        double springLength = 140.0;
        double springStrength = 0.04;
        double centerStrength = 0.015;
        double damping = 0.82;

        double cx = getWidth() > 0 ? getWidth() / 2 : 400;
        double cy = getHeight() > 0 ? getHeight() / 2 : 300;

        for (int i = 0; i < nodes.size(); i++) {
            NodeDto n1 = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                NodeDto n2 = nodes.get(j);
                double dx = n2.getX() - n1.getX();
                double dy = n2.getY() - n1.getY();
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 1.0) dist = 1.0;

                if (dist < 380) {
                    double force = (repulsion / (dist * dist));
                    double fx = (dx / dist) * force;
                    double fy = (dy / dist) * force;

                    n1.setVx(n1.getVx() - fx);
                    n1.setVy(n1.getVy() - fy);
                    n2.setVx(n2.getVx() + fx);
                    n2.setVy(n2.getVy() + fy);
                }
            }

            n1.setVx(n1.getVx() + (cx - n1.getX()) * centerStrength);
            n1.setVy(n1.getVy() + (cy - n1.getY()) * centerStrength);
        }

        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());

            if (src != null && tgt != null) {
                double dx = tgt.getX() - src.getX();
                double dy = tgt.getY() - src.getY();
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 1.0) dist = 1.0;

                double displacement = dist - springLength;
                double force = displacement * springStrength;
                double fx = (dx / dist) * force;
                double fy = (dy / dist) * force;

                src.setVx(src.getVx() + fx);
                src.setVy(src.getVy() + fy);
                tgt.setVx(tgt.getVx() - fx);
                tgt.setVy(tgt.getVy() - fy);
            }
        }

        for (NodeDto n : nodes) {
            if (n == draggedNode) continue;
            n.setX(n.getX() + n.getVx());
            n.setY(n.getY() + n.getVy());
            n.setVx(n.getVx() * damping);
            n.setVy(n.getVy() * damping);
        }
    }

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        gc.clearRect(0, 0, w, h);

        drawGrid(gc, w, h);

        if (nodes.isEmpty()) {
            gc.setFont(Font.font("System", FontWeight.BOLD, 15));
            gc.setFill(Color.web("#94A3B8"));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("Knowledge Graph Ready", w / 2, h / 2 - 14);

            gc.setFont(Font.font("System", FontWeight.NORMAL, 12));
            gc.setFill(Color.web("#64748B"));
            gc.fillText("Upload evidence files or click '↺ Refresh' once extraction finishes.", w / 2, h / 2 + 12);
            drawMinimap();
            return;
        }

        gc.save();
        gc.translate(panX, panY);
        gc.scale(zoom, zoom);

        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());
            if (src != null && tgt != null) {
                drawEdge(gc, src, tgt, edge, edge == selectedEdge, edge == hoveredEdge);
            }
        }

        for (NodeDto node : nodes) {
            drawNode(gc, node, node == selectedNode, node == hoveredNode);
        }

        gc.restore();

        drawMinimap();
    }

    private void drawGrid(GraphicsContext gc, double w, double h) {
        gc.setStroke(Color.web("#141b27"));
        gc.setLineWidth(1.0);
        double gridSize = 32.0 * zoom;
        double startX = (panX % gridSize + gridSize) % gridSize;
        double startY = (panY % gridSize + gridSize) % gridSize;

        for (double x = startX; x < w; x += gridSize) {
            gc.strokeLine(x, 0, x, h);
        }
        for (double y = startY; y < h; y += gridSize) {
            gc.strokeLine(0, y, w, y);
        }
    }

    private void drawEdge(GraphicsContext gc, NodeDto src, NodeDto tgt, EdgeDto edge, boolean isSelected, boolean isHovered) {
        double x1 = src.getX();
        double y1 = src.getY();
        double x2 = tgt.getX();
        double y2 = tgt.getY();

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1.0) return;

        double radiusSrc = getNodeRadius(src);
        double radiusTgt = getNodeRadius(tgt);

        double startX = x1 + (dx / dist) * radiusSrc;
        double startY = y1 + (dy / dist) * radiusSrc;
        double endX = x2 - (dx / dist) * (radiusTgt + 6);
        double endY = y2 - (dy / dist) * (radiusTgt + 6);

        Color strokeColor = isSelected ? Color.web("#60A5FA") : isHovered ? Color.web("#93C5FD") : Color.web("#334155");
        double lineWidth = isSelected ? 3.0 : isHovered ? 2.2 : 1.5;

        gc.setStroke(strokeColor);
        gc.setLineWidth(lineWidth);
        gc.strokeLine(startX, startY, endX, endY);

        drawArrowHead(gc, startX, startY, endX, endY, strokeColor);

        double midX = (startX + endX) / 2;
        double midY = (startY + endY) / 2;
        String relLabel = edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED";

        gc.setFont(Font.font("System", FontWeight.SEMI_BOLD, 9));
        double textWidth = relLabel.length() * 5.5 + 10;
        double textHeight = 16;

        gc.setFill(isSelected ? Color.web("#1E3A8A") : Color.web("#161F30"));
        gc.setStroke(strokeColor);
        gc.setLineWidth(1.0);
        gc.fillRoundRect(midX - textWidth / 2, midY - textHeight / 2, textWidth, textHeight, 6, 6);
        gc.strokeRoundRect(midX - textWidth / 2, midY - textHeight / 2, textWidth, textHeight, 6, 6);

        gc.setFill(isSelected ? Color.web("#FFFFFF") : Color.web("#94A3B8"));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(relLabel, midX, midY + 3.5);
    }

    private void drawArrowHead(GraphicsContext gc, double x1, double y1, double x2, double y2, Color color) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double arrowLength = 9.0;
        double arrowWidth = 5.0;

        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        double p1x = x2 - arrowLength * cos + arrowWidth * sin;
        double p1y = y2 - arrowLength * sin - arrowWidth * cos;

        double p2x = x2 - arrowLength * cos - arrowWidth * sin;
        double p2y = y2 - arrowLength * sin + arrowWidth * cos;

        gc.setFill(color);
        gc.fillPolygon(new double[]{x2, p1x, p2x}, new double[]{y2, p1y, p2y}, 3);
    }

    private void drawNode(GraphicsContext gc, NodeDto node, boolean isSelected, boolean isHovered) {
        double x = node.getX();
        double y = node.getY();
        double radius = getNodeRadius(node);
        Color typeColor = getTypeColor(node.getType());

        if (isSelected) {
            gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.35));
            gc.fillOval(x - radius - 8, y - radius - 8, (radius + 8) * 2, (radius + 8) * 2);
            gc.setStroke(typeColor.brighter());
            gc.setLineWidth(2.5);
            gc.strokeOval(x - radius - 3, y - radius - 3, (radius + 3) * 2, (radius + 3) * 2);
        } else if (isHovered) {
            gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.2));
            gc.fillOval(x - radius - 5, y - radius - 5, (radius + 5) * 2, (radius + 5) * 2);
        }

        gc.setFill(Color.web("#0F172A"));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);

        gc.setStroke(typeColor);
        gc.setLineWidth(isSelected ? 3.0 : 2.0);
        gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);

        String symbol = getTypeSymbol(node.getType());
        gc.setFont(Font.font("System", FontWeight.BOLD, 14));
        gc.setFill(typeColor.brighter());
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(symbol, x, y - 4);

        String label = node.getName() != null ? node.getName() : "Entity";
        if (label.length() > 16) {
            label = label.substring(0, 14) + "..";
        }
        gc.setFont(Font.font("System", FontWeight.SEMI_BOLD, 10));
        gc.setFill(Color.web("#E2E8F0"));
        gc.fillText(label, x, y + 14);

        String sub = node.getType() != null ? node.getType().toLowerCase(Locale.ROOT) : "";
        gc.setFont(Font.font("System", FontWeight.NORMAL, 8));
        gc.setFill(Color.web("#94A3B8"));
        gc.fillText(sub, x, y + radius + 12);
    }

    private void drawMinimap() {
        GraphicsContext mgc = minimapCanvas.getGraphicsContext2D();
        double mw = minimapCanvas.getWidth();
        double mh = minimapCanvas.getHeight();
        mgc.clearRect(0, 0, mw, mh);

        mgc.setFill(Color.web("#0D131F"));
        mgc.fillRect(0, 0, mw, mh);

        if (nodes.isEmpty()) return;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (NodeDto n : nodes) {
            minX = Math.min(minX, n.getX());
            maxX = Math.max(maxX, n.getX());
            minY = Math.min(minY, n.getY());
            maxY = Math.max(maxY, n.getY());
        }

        double spanX = Math.max(100, maxX - minX + 100);
        double spanY = Math.max(100, maxY - minY + 100);
        double scale = Math.min((mw - 16) / spanX, (mh - 16) / spanY);

        double offX = (mw - spanX * scale) / 2 - minX * scale;
        double offY = (mh - spanY * scale) / 2 - minY * scale;

        mgc.setStroke(Color.web("#1E293B"));
        mgc.setLineWidth(1.0);
        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());
            if (src != null && tgt != null) {
                mgc.strokeLine(src.getX() * scale + offX, src.getY() * scale + offY,
                        tgt.getX() * scale + offX, tgt.getY() * scale + offY);
            }
        }

        for (NodeDto n : nodes) {
            Color c = getTypeColor(n.getType());
            mgc.setFill(c);
            double nx = n.getX() * scale + offX;
            double ny = n.getY() * scale + offY;
            mgc.fillOval(nx - 2, ny - 2, 4, 4);
        }

        double viewMinX = -panX / zoom;
        double viewMinY = -panY / zoom;
        double viewMaxX = (canvas.getWidth() - panX) / zoom;
        double viewMaxY = (canvas.getHeight() - panY) / zoom;

        double rx = viewMinX * scale + offX;
        double ry = viewMinY * scale + offY;
        double rw = (viewMaxX - viewMinX) * scale;
        double rh = (viewMaxY - viewMinY) * scale;

        mgc.setStroke(Color.web("#3B82F6"));
        mgc.setLineWidth(1.5);
        mgc.strokeRect(Math.max(0, rx), Math.max(0, ry), Math.min(mw, rw), Math.min(mh, rh));
    }

    private NodeDto findNodeAt(double screenX, double screenY) {
        double worldX = (screenX - panX) / zoom;
        double worldY = (screenY - panY) / zoom;

        for (int i = nodes.size() - 1; i >= 0; i--) {
            NodeDto n = nodes.get(i);
            double dx = worldX - n.getX();
            double dy = worldY - n.getY();
            double r = getNodeRadius(n);
            if (dx * dx + dy * dy <= r * r) {
                return n;
            }
        }
        return null;
    }

    private EdgeDto findEdgeAt(double screenX, double screenY) {
        double worldX = (screenX - panX) / zoom;
        double worldY = (screenY - panY) / zoom;

        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());
            if (src != null && tgt != null) {
                double dist = pointToLineSegmentDistance(worldX, worldY, src.getX(), src.getY(), tgt.getX(), tgt.getY());
                if (dist < 12.0) {
                    return edge;
                }
            }
        }
        return null;
    }

    private double pointToLineSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
        if (l2 == 0) return Math.sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1));
        double t = Math.max(0, Math.min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2));
        double projX = x1 + t * (x2 - x1);
        double projY = y1 + t * (y2 - y1);
        return Math.sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY));
    }

    private NodeDto getNode(String idOrName) {
        if (idOrName == null) return null;
        return nodeLookup.get(idOrName.toLowerCase(Locale.ROOT));
    }

    private double getNodeRadius(NodeDto n) {
        int mentions = n.getMentions() != null ? n.getMentions() : 1;
        return Math.min(52.0, 36.0 + Math.log(mentions + 1) * 4.0);
    }

    private Color getTypeColor(String type) {
        if (type == null) return Color.web("#94A3B8");
        String t = type.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "PERSON" -> Color.web("#3B82F6");
            case "PHONE_NUMBER", "PHONE" -> Color.web("#06B6D4");
            case "DOCUMENT", "TRANSCRIPT" -> Color.web("#10B981");
            case "LOCATION" -> Color.web("#F59E0B");
            case "ORGANIZATION" -> Color.web("#8B5CF6");
            case "EVENT" -> Color.web("#F43F5E");
            default -> Color.web("#64748B");
        };
    }

    private String getTypeSymbol(String type) {
        if (type == null) return "●";
        String t = type.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "PERSON" -> "👤";
            case "PHONE_NUMBER", "PHONE" -> "📞";
            case "DOCUMENT", "TRANSCRIPT" -> "📄";
            case "LOCATION" -> "📍";
            case "ORGANIZATION" -> "🏢";
            case "EVENT" -> "⚡";
            default -> "●";
        };
    }

    public void setOnNodeSelected(Consumer<NodeDto> onNodeSelected) { this.onNodeSelected = onNodeSelected; }
    public void setOnEdgeSelected(Consumer<EdgeDto> onEdgeSelected) { this.onEdgeSelected = onEdgeSelected; }
    public void setOnSelectionCleared(Runnable onSelectionCleared) { this.onSelectionCleared = onSelectionCleared; }
}
