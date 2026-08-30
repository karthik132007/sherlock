package com.sherlock.ui.view;

import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
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

    public enum LayoutMode {
        FORCE_DIRECTED("⚡ Physics Force"),
        DEGREE_HIERARCHY("👑 Degree Hierarchy"),
        RADIAL_HUB("🎯 Centrality Hub"),
        CIRCULAR("⭕ Circular");

        private final String label;

        LayoutMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final Canvas canvas = new Canvas(400, 300);
    private final Canvas minimapCanvas = new Canvas(140, 90);
    private final List<NodeDto> nodes = new ArrayList<>();
    private final List<EdgeDto> edges = new ArrayList<>();
    private final Map<String, NodeDto> nodeLookup = new HashMap<>();
    private final Map<String, Integer> nodeDegreeMap = new HashMap<>();

    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private double dragStartX, dragStartY;
    private boolean isMinimapDragging = false;
    private NodeDto draggedNode = null;
    private NodeDto selectedNode = null;
    private EdgeDto selectedEdge = null;
    private NodeDto hoveredNode = null;
    private EdgeDto hoveredEdge = null;

    private boolean isPanMode = false;
    private boolean isSimulating = true;
    private int simulationTicks = 0;
    private LayoutMode currentLayout = LayoutMode.FORCE_DIRECTED;

    private String filterType = "ALL";
    private String searchQuery = "";
    private final Set<String> highlightedNodeIds = new HashSet<>();
    private final Set<String> highlightedRelationIds = new HashSet<>();

    private Consumer<NodeDto> onNodeSelected;
    private Consumer<EdgeDto> onEdgeSelected;
    private Runnable onSelectionCleared;

    private final AnimationTimer renderLoop;
    private Button playPauseBtn;
    private TextField searchField;

    public GraphCanvas() {
        getStyleClass().add("investigation-canvas");
        setMinSize(300, 200);
        setFocusTraversable(true);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldVal, newVal) -> render());
        heightProperty().addListener((obs, oldVal, newVal) -> render());

        HBox topToolbar = buildGraphTopToolbar();
        StackPane.setAlignment(topToolbar, Pos.TOP_CENTER);
        StackPane.setMargin(topToolbar, new Insets(10, 12, 0, 12));

        VBox minimapBox = buildMinimap();
        StackPane.setAlignment(minimapBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(minimapBox, new Insets(12));

        HBox legendBox = buildLegend();
        StackPane.setAlignment(legendBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(legendBox, new Insets(0, 0, 12, 160));

        getChildren().addAll(canvas, topToolbar, minimapBox, legendBox);

        setupMouseHandlers();
        setupMinimapHandlers();

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (isSimulating && currentLayout == LayoutMode.FORCE_DIRECTED && simulationTicks < 350
                        && !nodes.isEmpty()) {
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
        nodeDegreeMap.clear();
        selectedNode = null;
        selectedEdge = null;
        hoveredNode = null;
        hoveredEdge = null;
        if (searchField != null) searchField.clear();
        searchQuery = "";
        highlightedNodeIds.clear();
        highlightedRelationIds.clear();

        if (data != null) {
            if (data.getNodes() != null) nodes.addAll(data.getNodes());
            if (data.getEdges() != null) edges.addAll(data.getEdges());
        }

        if (nodes.isEmpty()) {
            zoom = 1.0; panX = 0; panY = 0;
            isSimulating = false;
            render();
            return;
        }

        for (NodeDto n : nodes) {
            if (n.getId() != null) nodeLookup.put(n.getId().toLowerCase(Locale.ROOT), n);
            if (n.getName() != null) nodeLookup.put(n.getName().toLowerCase(Locale.ROOT), n);
            nodeDegreeMap.put(n.getId() != null ? n.getId() : n.getName(), 0);
        }

        for (EdgeDto e : edges) {
            NodeDto src = getNode(e.getSource());
            NodeDto tgt = getNode(e.getTarget());
            if (src != null) {
                String k = src.getId() != null ? src.getId() : src.getName();
                nodeDegreeMap.put(k, nodeDegreeMap.getOrDefault(k, 0) + 1);
            }
            if (tgt != null) {
                String k = tgt.getId() != null ? tgt.getId() : tgt.getName();
                nodeDegreeMap.put(k, nodeDegreeMap.getOrDefault(k, 0) + 1);
            }
        }

        applyLayout(currentLayout);
    }

    /** Highlight the exact evidence selected by the Python investigation query agent. */
    public void highlightQueryResults(Collection<String> nodeIds, Collection<String> relationIds) {
        highlightedNodeIds.clear();
        highlightedRelationIds.clear();
        if (nodeIds != null) {
            for (String id : nodeIds) if (id != null && !id.isBlank()) highlightedNodeIds.add(id.toLowerCase(Locale.ROOT));
        }
        if (relationIds != null) {
            for (String id : relationIds) if (id != null && !id.isBlank()) highlightedRelationIds.add(id.toLowerCase(Locale.ROOT));
        }
        render();
    }

    /** Remove the active investigation-query highlight and return to the normal graph view. */
    public void clearQueryHighlights() {
        highlightedNodeIds.clear();
        highlightedRelationIds.clear();
        selectedNode = null;
        selectedEdge = null;
        hoveredNode = null;
        hoveredEdge = null;
        if (onSelectionCleared != null) onSelectionCleared.run();
        render();
    }

    public void applyLayout(LayoutMode mode) {
        this.currentLayout = mode;
        if (nodes.isEmpty()) return;

        double cx = getWidth() > 0 ? getWidth() / 2 : 400;
        double cy = getHeight() > 0 ? getHeight() / 2 : 300;

        switch (mode) {
            case DEGREE_HIERARCHY -> {
                List<NodeDto> sorted = new ArrayList<>(nodes);
                sorted.sort((a, b) -> Integer.compare(getNodeDegree(b), getNodeDegree(a)));

                int tierCount = Math.max(1, (int) Math.ceil(Math.sqrt(sorted.size())));
                int perTier = (int) Math.ceil((double) sorted.size() / tierCount);

                double tierHeight = Math.max(120, (getHeight() > 0 ? getHeight() * 0.75 : 400) / Math.max(1, tierCount));
                double startY = cy - ((tierCount - 1) * tierHeight) / 2.0;

                for (int i = 0; i < sorted.size(); i++) {
                    int tier = i / perTier;
                    int col = i % perTier;
                    int inThisTier = Math.min(perTier, sorted.size() - tier * perTier);

                    double spacingX = Math.max(140, 700.0 / Math.max(1, inThisTier));
                    double startX = cx - ((inThisTier - 1) * spacingX) / 2.0;

                    NodeDto n = sorted.get(i);
                    n.setX(startX + col * spacingX + (Math.random() - 0.5) * 20);
                    n.setY(startY + tier * tierHeight + (Math.random() - 0.5) * 20);
                    n.setVx(0); n.setVy(0);
                }
                isSimulating = false;
            }
            case RADIAL_HUB -> {
                List<NodeDto> sorted = new ArrayList<>(nodes);
                sorted.sort((a, b) -> Integer.compare(getNodeDegree(b), getNodeDegree(a)));

                int hubCount = Math.min(4, Math.max(1, sorted.size() / 4));
                double innerRadius = Math.min(cx, cy) * 0.28;
                double outerRadius = Math.min(cx, cy) * 0.68;

                for (int i = 0; i < sorted.size(); i++) {
                    NodeDto n = sorted.get(i);
                    if (i < hubCount) {
                        double angle = (2 * Math.PI * i) / Math.max(1, hubCount);
                        n.setX(cx + innerRadius * Math.cos(angle));
                        n.setY(cy + innerRadius * Math.sin(angle));
                    } else {
                        int outerIdx = i - hubCount;
                        int totalOuter = sorted.size() - hubCount;
                        double angle = (2 * Math.PI * outerIdx) / Math.max(1, totalOuter);
                        n.setX(cx + outerRadius * Math.cos(angle) + (Math.random() - 0.5) * 30);
                        n.setY(cy + outerRadius * Math.sin(angle) + (Math.random() - 0.5) * 30);
                    }
                    n.setVx(0); n.setVy(0);
                }
                isSimulating = false;
            }
            case CIRCULAR -> {
                double radius = Math.max(140, Math.min(cx, cy) * 0.65);
                for (int i = 0; i < nodes.size(); i++) {
                    NodeDto n = nodes.get(i);
                    double angle = (2 * Math.PI * i) / Math.max(1, nodes.size());
                    n.setX(cx + radius * Math.cos(angle));
                    n.setY(cy + radius * Math.sin(angle));
                    n.setVx(0); n.setVy(0);
                }
                isSimulating = false;
            }
            case FORCE_DIRECTED -> {
                double radius = Math.max(120, Math.min(cx, cy) * 0.60);
                for (int i = 0; i < nodes.size(); i++) {
                    NodeDto n = nodes.get(i);
                    double angle = (2 * Math.PI * i) / Math.max(1, nodes.size());
                    n.setX(cx + radius * Math.cos(angle) + (Math.random() - 0.5) * 60);
                    n.setY(cy + radius * Math.sin(angle) + (Math.random() - 0.5) * 60);
                    n.setVx(0); n.setVy(0);
                }
                simulationTicks = 0;
                isSimulating = true;
            }
        }
        fitToView();
    }

    public void selectAndFocusNode(NodeDto node) {
        if (node == null) return;
        this.selectedNode = node;
        this.selectedEdge = null;

        double canvasW = Math.max(120, canvas.getWidth());
        double canvasH = Math.max(120, canvas.getHeight());
        panX = (canvasW / 2) - node.getX() * zoom;
        panY = (canvasH / 2) - node.getY() * zoom;

        if (onNodeSelected != null) onNodeSelected.accept(node);
        render();
    }

    private HBox buildGraphTopToolbar() {
        HBox toolbar = new HBox(8);
        toolbar.setMaxHeight(Region.USE_PREF_SIZE);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("graph-top-toolbar");
        toolbar.setPadding(new Insets(6, 14, 6, 14));

        // Search
        HBox searchBox = new HBox(4);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("graph-search-box");

        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        searchField = new TextField();
        searchField.setPromptText("Search entity / connection...");
        searchField.getStyleClass().add("graph-search");
        searchField.setPrefWidth(180);
        searchField.textProperty().addListener((obs, oldV, newV) -> {
            searchQuery = newV != null ? newV.trim().toLowerCase(Locale.ROOT) : "";
            render();
        });
        searchField.setOnAction(e -> {
            if (!searchQuery.isEmpty()) {
                NodeDto match = nodes.stream()
                        .filter(n -> n.getName() != null && n.getName().toLowerCase(Locale.ROOT).contains(searchQuery))
                        .findFirst().orElse(null);
                if (match != null) selectAndFocusNode(match);
            }
        });

        Button filterToggle = new Button("⧩");
        filterToggle.getStyleClass().add("toolbar-icon-btn");
        filterToggle.setTooltip(new Tooltip("Filter entities"));

        searchBox.getChildren().addAll(searchIcon, searchField, filterToggle);

        // Separator
        Region sep1 = new Region();
        sep1.setPrefWidth(1);
        sep1.setMinWidth(1);
        sep1.setMaxWidth(1);
        sep1.setStyle("-fx-background-color: #e2e8f0;");
        sep1.setPrefHeight(28);

        // Layout, Filters, Focus buttons
        ComboBox<LayoutMode> layoutCombo = new ComboBox<>();
        layoutCombo.getItems().addAll(LayoutMode.values());
        layoutCombo.setValue(LayoutMode.FORCE_DIRECTED);
        layoutCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LayoutMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        });
        layoutCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(LayoutMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "⊞ Layout");
            }
        });
        layoutCombo.getStyleClass().add("layout-combo");
        layoutCombo.setOnAction(e -> applyLayout(layoutCombo.getValue()));

        // Filter popup button
        MenuButton filtersBtn = new MenuButton("⧫ Filters");
        filtersBtn.getStyleClass().add("toolbar-menu-btn");
        String[] types = { "ALL", "PERSON", "LOCATION", "ORG", "DOC", "PHONE", "MESSAGE", "MEDICAL", "EVENT" };
        for (String t : types) {
            CheckMenuItem item = new CheckMenuItem(t);
            item.setSelected("ALL".equals(t));
            item.setOnAction(ev -> {
                filterType = t;
                for (MenuItem mi : filtersBtn.getItems()) {
                    if (mi instanceof CheckMenuItem ci) ci.setSelected(ci.getText().equals(t));
                }
                render();
            });
            filtersBtn.getItems().add(item);
        }

        Button focusBtn = new Button("◎ Focus");
        focusBtn.getStyleClass().add("toolbar-text-btn");
        focusBtn.setOnAction(e -> fitToView());

        // Separator
        Region sep2 = new Region();
        sep2.setPrefWidth(1);
        sep2.setMinWidth(1);
        sep2.setMaxWidth(1);
        sep2.setStyle("-fx-background-color: #e2e8f0;");
        sep2.setPrefHeight(28);

        // Zoom controls
        Button zoomOutBtn = new Button("−");
        zoomOutBtn.getStyleClass().add("toolbar-icon-btn");
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out"));
        zoomOutBtn.setOnAction(e -> { zoom = Math.max(0.15, zoom / 1.2); render(); });

        Button zoomInBtn = new Button("+");
        zoomInBtn.getStyleClass().add("toolbar-icon-btn");
        zoomInBtn.setTooltip(new Tooltip("Zoom In"));
        zoomInBtn.setOnAction(e -> { zoom = Math.min(3.5, zoom * 1.2); render(); });

        Button fitBtn = new Button("⛶ Fit");
        fitBtn.getStyleClass().add("toolbar-text-btn");
        fitBtn.setTooltip(new Tooltip("Fit Graph to Screen"));
        fitBtn.setOnAction(e -> fitToView());

        // Play/pause
        playPauseBtn = new Button("⏸");
        playPauseBtn.setTooltip(new Tooltip("Pause / Resume Physics"));
        playPauseBtn.getStyleClass().add("toolbar-icon-btn");
        playPauseBtn.setOnAction(e -> {
            isSimulating = !isSimulating;
            if (isSimulating) {
                simulationTicks = 0;
                playPauseBtn.setText("⏸");
            } else {
                playPauseBtn.setText("▶");
            }
        });

        Button clearHighlightsBtn = new Button("✕");
        clearHighlightsBtn.setTooltip(new Tooltip("Clear Query Highlights"));
        clearHighlightsBtn.getStyleClass().add("toolbar-icon-btn");
        clearHighlightsBtn.setOnAction(e -> clearQueryHighlights());

        toolbar.getChildren().addAll(searchBox, sep1, layoutCombo, filtersBtn, focusBtn, sep2, zoomOutBtn, zoomInBtn, fitBtn, playPauseBtn, clearHighlightsBtn);
        return toolbar;
    }

    private void updateFilterPillStyle(Button pill, boolean isSelected) {
        if (isSelected) {
            pill.setStyle("-fx-background-color: #2563EB; -fx-text-fill: #ffffff; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 8px; -fx-background-radius: 5px; -fx-cursor: hand;");
        } else {
            pill.setStyle("-fx-background-color: #F1F5F9; -fx-text-fill: #475569; -fx-font-size: 12px; -fx-padding: 4px 8px; -fx-background-radius: 5px; -fx-cursor: hand;");
        }
    }

    // buildToolbar() removed — merged into buildGraphTopToolbar()

    private VBox buildMinimap() {
        VBox box = new VBox(4);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.getStyleClass().add("graph-overlay");

        Label title = new Label("Map overview");
        title.setStyle("-fx-font-size: 12.5px; -fx-text-fill: #475569; -fx-font-weight: bold;");

        minimapCanvas.setWidth(130);
        minimapCanvas.setHeight(85);

        box.getChildren().addAll(title, minimapCanvas);
        return box;
    }

    private HBox buildLegend() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.getStyleClass().add("graph-legend");

        box.getChildren().add(legendItem(Color.web("#3B82F6"), "Person"));
        box.getChildren().add(legendItem(Color.web("#10B981"), "Location"));
        box.getChildren().add(legendItem(Color.web("#8B5CF6"), "Organization"));
        box.getChildren().add(legendItem(Color.web("#F59E0B"), "Document"));
        box.getChildren().add(legendItem(Color.web("#06B6D4"), "Phone"));
        box.getChildren().add(legendItem(Color.web("#0D9488"), "Message"));
        box.getChildren().add(legendItem(Color.web("#F43F5E"), "Medical"));
        box.getChildren().add(legendItem(Color.web("#64748B"), "Other"));

        return box;
    }

    private HBox legendItem(Color color, String text) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        Circle dot = new Circle(4.5, color);
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B; -fx-font-weight: 500;");
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
            double zoomFactor = e.getDeltaY() > 0 ? 1.14 : 1 / 1.14;

            double newZoom = Math.max(0.15, Math.min(4.0, zoom * zoomFactor));
            if (newZoom != zoom) {
                panX = mouseX - (mouseX - panX) * (newZoom / zoom);
                panY = mouseY - (mouseY - panY) * (newZoom / zoom);
                zoom = newZoom;
                render();
            }
            e.consume();
        });
    }

    private void setupMinimapHandlers() {
        minimapCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            isMinimapDragging = true;
            panFromMinimap(e.getX(), e.getY());
        });
        minimapCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (isMinimapDragging) panFromMinimap(e.getX(), e.getY());
        });
        minimapCanvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> isMinimapDragging = false);
    }

    private void panFromMinimap(double mx, double my) {
        if (nodes.isEmpty()) return;
        double mw = minimapCanvas.getWidth();
        double mh = minimapCanvas.getHeight();

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

        double targetWorldX = (mx - offX) / scale;
        double targetWorldY = (my - offY) / scale;

        double canvasW = Math.max(120, canvas.getWidth());
        double canvasH = Math.max(120, canvas.getHeight());

        panX = (canvasW / 2) - targetWorldX * zoom;
        panY = (canvasH / 2) - targetWorldY * zoom;

        render();
    }

    public void fitToView() {
        if (nodes.isEmpty()) {
            zoom = 1.0; panX = 0; panY = 0; render(); return;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (NodeDto n : nodes) {
            minX = Math.min(minX, n.getX());
            maxX = Math.max(maxX, n.getX());
            minY = Math.min(minY, n.getY());
            maxY = Math.max(maxY, n.getY());
        }

        double graphW = Math.max(120, maxX - minX + 180);
        double graphH = Math.max(120, maxY - minY + 180);

        double canvasW = Math.max(120, canvas.getWidth());
        double canvasH = Math.max(120, canvas.getHeight());

        zoom = Math.min(1.2, Math.min(canvasW / graphW, canvasH / graphH) * 0.85);
        if (zoom < 0.2) zoom = 0.2;

        double graphCenterX = (minX + maxX) / 2;
        double graphCenterY = (minY + maxY) / 2;

        panX = (canvasW / 2) - graphCenterX * zoom;
        panY = (canvasH / 2) - graphCenterY * zoom;

        render();
    }

    private void stepPhysics() {
        if (nodes.isEmpty()) return;

        double repulsion = 5200.0;
        double springLength = 150.0;
        double springStrength = 0.045;
        double centerStrength = 0.018;
        double damping = 0.80;

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

                if (dist < 420) {
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
            gc.setFill(Color.web("#334155"));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("Your investigation graph will appear here", w / 2, h / 2 - 14);

            gc.setFont(Font.font("System", FontWeight.NORMAL, 12));
            gc.setFill(Color.web("#64748B"));
            gc.fillText("Add evidence, run analysis, then explore every fact with its source context.", w / 2, h / 2 + 12);
            drawMinimap();
            return;
        }

        gc.save();
        gc.translate(panX, panY);
        gc.scale(zoom, zoom);

        Set<NodeDto> activeNeighbors = new HashSet<>();
        Set<EdgeDto> activeEdges = new HashSet<>();
        NodeDto focusNode = selectedNode != null ? selectedNode : hoveredNode;

        if (focusNode != null) {
            activeNeighbors.add(focusNode);
            for (EdgeDto edge : edges) {
                NodeDto src = getNode(edge.getSource());
                NodeDto tgt = getNode(edge.getTarget());
                if (src == focusNode && tgt != null) {
                    activeNeighbors.add(tgt);
                    activeEdges.add(edge);
                } else if (tgt == focusNode && src != null) {
                    activeNeighbors.add(src);
                    activeEdges.add(edge);
                }
            }
        }

        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());
            if (src != null && tgt != null) {
                boolean isEdgeSelected = (edge == selectedEdge);
                boolean isEdgeHovered = (edge == hoveredEdge);
                boolean isEdgeHighlighted = isQueryHighlighted(edge);
                boolean isEdgeActive = focusNode == null || activeEdges.contains(edge) || isEdgeSelected || isEdgeHovered || isEdgeHighlighted;
                double opacity = isEdgeActive ? 1.0 : 0.12;
                drawEdge(gc, src, tgt, edge, isEdgeSelected, isEdgeHovered, isEdgeHighlighted, opacity);
            }
        }

        List<NodeDto> renderList = new ArrayList<>(nodes);
        renderList.sort(Comparator.comparingInt(this::getNodeDegree));

        for (NodeDto node : renderList) {
            boolean isSel = (node == selectedNode);
            boolean isHov = (node == hoveredNode);
            boolean isMatchSearch = matchesSearch(node);
            boolean isMatchFilter = matchesFilter(node);
            boolean isQueryHighlighted = isQueryHighlighted(node);
            boolean isDimmed = !isMatchFilter || (!searchQuery.isEmpty() && !isMatchSearch) || (focusNode != null && !activeNeighbors.contains(node))
                    || ((!highlightedNodeIds.isEmpty() || !highlightedRelationIds.isEmpty()) && !isQueryHighlighted);
            double opacity = isDimmed ? 0.18 : 1.0;
            drawNode(gc, node, isSel, isHov, isMatchSearch && !searchQuery.isEmpty(), isQueryHighlighted, opacity);
        }

        gc.restore();

        if (hoveredNode != null && draggedNode == null) {
            drawNodeTooltip(gc, hoveredNode);
        }

        drawMinimap();
    }

    private boolean matchesFilter(NodeDto node) {
        if ("ALL".equalsIgnoreCase(filterType)) return true;
        if (node.getType() == null) return false;
        String t = node.getType().toUpperCase(Locale.ROOT);
        return switch (filterType.toUpperCase(Locale.ROOT)) {
            case "PERSON" -> t.contains("PERSON");
            case "LOCATION" -> t.contains("LOCATION");
            case "ORG", "ORGANIZATION" -> t.contains("ORG");
            case "DOC", "DOCUMENT" -> t.contains("DOC") || t.contains("TRANSCRIPT");
            case "PHONE" -> t.contains("PHONE");
            case "MESSAGE" -> t.contains("MESSAGE") || t.contains("MSG");
            case "MEDICAL" -> t.contains("MEDICAL") || t.contains("HOSPITAL") || t.contains("HEALTH");
            case "EVENT" -> t.contains("EVENT");
            default -> true;
        };
    }

    private boolean matchesSearch(NodeDto node) {
        if (searchQuery.isEmpty()) return true;
        String nName = node.getName() != null ? node.getName().toLowerCase(Locale.ROOT) : "";
        String nType = node.getType() != null ? node.getType().toLowerCase(Locale.ROOT) : "";
        if (nName.contains(searchQuery) || nType.contains(searchQuery)) return true;
        if (node.getAliases() != null) {
            for (String a : node.getAliases()) {
                if (a != null && a.toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
            }
        }
        return false;
    }

    private void drawGrid(GraphicsContext gc, double w, double h) {
        gc.setStroke(Color.web("#CBD5E1", 0.45));
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

    private void drawEdge(GraphicsContext gc, NodeDto src, NodeDto tgt, EdgeDto edge, boolean isSelected, boolean isHovered, boolean isQueryHighlighted, double opacity) {
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

        Color baseColor = isSelected ? Color.web("#2563EB") : isQueryHighlighted ? Color.web("#D946EF") : isHovered ? Color.web("#60A5FA") : Color.web("#94A3B8");
        Color strokeColor = Color.color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), opacity);

        double lineWidth = isSelected || isQueryHighlighted ? 3.2 : isHovered ? 2.4 : 1.5;

        gc.setStroke(strokeColor);
        gc.setLineWidth(lineWidth);
        gc.strokeLine(startX, startY, endX, endY);
        drawArrowHead(gc, startX, startY, endX, endY, strokeColor);

        if (opacity > 0.3) {
            double midX = (startX + endX) / 2;
            double midY = (startY + endY) / 2;
            String relLabel = edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED";

            gc.setFont(Font.font("System", FontWeight.SEMI_BOLD, 9));
            double textWidth = relLabel.length() * 5.5 + 12;
            double textHeight = 16;

            Color badgeBg = isSelected ? Color.web("#DBEAFE") : Color.web("#FFFFFF");
            gc.setFill(Color.color(badgeBg.getRed(), badgeBg.getGreen(), badgeBg.getBlue(), opacity));
            gc.setStroke(strokeColor);
            gc.setLineWidth(1.0);
            gc.fillRoundRect(midX - textWidth / 2, midY - textHeight / 2, textWidth, textHeight, 6, 6);
            gc.strokeRoundRect(midX - textWidth / 2, midY - textHeight / 2, textWidth, textHeight, 6, 6);

            Color textCol = isSelected ? Color.web("#1E40AF") : Color.web("#475569");
            gc.setFill(Color.color(textCol.getRed(), textCol.getGreen(), textCol.getBlue(), opacity));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(relLabel, midX, midY + 3.5);
        }
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
        gc.fillPolygon(new double[] { x2, p1x, p2x }, new double[] { y2, p1y, p2y }, 3);
    }

    private void drawNode(GraphicsContext gc, NodeDto node, boolean isSelected, boolean isHovered, boolean isSearchMatch, boolean isQueryHighlighted, double opacity) {
        double x = node.getX();
        double y = node.getY();
        double radius = getNodeRadius(node);
        int degree = getNodeDegree(node);
        Color typeColor = getTypeColor(node.getType());

        // Outer glow for high-degree nodes
        if (degree >= 3 && opacity > 0.5) {
            Color glowColor = Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.12 + Math.min(0.18, degree * 0.02));
            gc.setFill(glowColor);
            gc.fillOval(x - radius - 14, y - radius - 14, (radius + 14) * 2, (radius + 14) * 2);
        }

        // Selection / hover / highlight rings
        if (isSelected) {
            // Soft colored halo
            gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.20 * opacity));
            gc.fillOval(x - radius - 10, y - radius - 10, (radius + 10) * 2, (radius + 10) * 2);
            // Bright ring
            gc.setStroke(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), opacity));
            gc.setLineWidth(3.0);
            gc.strokeOval(x - radius - 4, y - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
        } else if (isHovered) {
            gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.15 * opacity));
            gc.fillOval(x - radius - 8, y - radius - 8, (radius + 8) * 2, (radius + 8) * 2);
        } else if (isQueryHighlighted) {
            gc.setStroke(Color.web("#D946EF"));
            gc.setLineWidth(3.2);
            gc.strokeOval(x - radius - 5, y - radius - 5, (radius + 5) * 2, (radius + 5) * 2);
        } else if (isSearchMatch) {
            gc.setStroke(Color.web("#F59E0B"));
            gc.setLineWidth(2.5);
            gc.strokeOval(x - radius - 4, y - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
        }

        // White fill circle
        gc.setFill(Color.color(1.0, 1.0, 1.0, opacity));
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);

        // Colored border ring — thicker and more prominent
        gc.setStroke(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), opacity));
        gc.setLineWidth(isSelected ? 3.5 : degree >= 3 ? 3.0 : 2.5);
        gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);

        // Inner colored icon circle (matching reference design)
        double innerR = radius * 0.55;
        Color innerBg = Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), 0.12 * opacity);
        gc.setFill(innerBg);
        gc.fillOval(x - innerR, y - innerR - 2, innerR * 2, innerR * 2);

        // Icon symbol
        String symbol = getTypeSymbol(node.getType());
        gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(13, radius * 0.45)));
        Color symColor = typeColor.darker();
        gc.setFill(Color.color(symColor.getRed(), symColor.getGreen(), symColor.getBlue(), opacity));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(symbol, x, y - 1);

        // Entity name label below node
        String label = node.getName() != null ? node.getName() : "Entity";
        if (label.length() > 18) label = label.substring(0, 16) + "…";
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.setFill(Color.color(0.06, 0.09, 0.16, opacity));
        gc.fillText(label, x, y + radius + 14);

        // Degree badge (top-right corner)
        if (degree > 0 && opacity > 0.4) {
            double badgeX = x + radius * 0.65;
            double badgeY = y - radius * 0.65;
            double bR = 10.0;
            gc.setFill(Color.color(1.0, 1.0, 1.0, opacity));
            gc.fillOval(badgeX - bR, badgeY - bR, bR * 2, bR * 2);
            gc.setStroke(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), opacity * 0.6));
            gc.setLineWidth(1.5);
            gc.strokeOval(badgeX - bR, badgeY - bR, bR * 2, bR * 2);
            gc.setFont(Font.font("System", FontWeight.BOLD, 9));
            gc.setFill(Color.color(typeColor.darker().getRed(), typeColor.darker().getGreen(), typeColor.darker().getBlue(), opacity));
            gc.fillText(String.valueOf(degree), badgeX, badgeY + 3.5);
        }
    }

    private boolean isQueryHighlighted(NodeDto node) {
        if (node == null) return false;
        return (node.getId() != null && highlightedNodeIds.contains(node.getId().toLowerCase(Locale.ROOT)))
                || (node.getName() != null && highlightedNodeIds.contains(node.getName().toLowerCase(Locale.ROOT)));
    }

    private boolean isQueryHighlighted(EdgeDto edge) {
        if (edge == null) return false;
        if (edge.getRelationId() != null && highlightedRelationIds.contains(edge.getRelationId().toLowerCase(Locale.ROOT))) return true;
        return isQueryHighlighted(getNode(edge.getSource())) || isQueryHighlighted(getNode(edge.getTarget()));
    }

    private void drawNodeTooltip(GraphicsContext gc, NodeDto node) {
        double screenX = node.getX() * zoom + panX;
        double screenY = node.getY() * zoom + panY - getNodeRadius(node) * zoom - 16;

        int degree = getNodeDegree(node);
        int mentions = node.getMentions() != null ? node.getMentions() : 1;
        String name = node.getName() != null ? node.getName() : "Entity";
        String type = node.getType() != null ? node.getType() : "ENTITY";

        String title = name + " (" + type + ")";
        String stats = "Connections: " + degree + "  |  Mentions: " + mentions;

        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        double w = Math.max(160, Math.max(title.length() * 6.5, stats.length() * 6.0) + 20);
        double h = 42;

        double tx = Math.max(10, Math.min(canvas.getWidth() - w - 10, screenX - w / 2));
        double ty = Math.max(10, screenY - h);

        gc.setFill(Color.web("#FFFFFF", 0.98));
        gc.fillRoundRect(tx, ty, w, h, 8, 8);
        gc.setStroke(Color.web("#CBD5E1"));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(tx, ty, w, h, 8, 8);

        gc.setFill(Color.web("#0F172A"));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(title, tx + 10, ty + 16);

        gc.setFont(Font.font("System", FontWeight.NORMAL, 9.5));
        gc.setFill(Color.web("#64748B"));
        gc.fillText(stats, tx + 10, ty + 32);
    }

    private void drawMinimap() {
        GraphicsContext mgc = minimapCanvas.getGraphicsContext2D();
        double mw = minimapCanvas.getWidth();
        double mh = minimapCanvas.getHeight();
        mgc.clearRect(0, 0, mw, mh);

        mgc.setFill(Color.web("#F8FAFC"));
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

        mgc.setStroke(Color.web("#cbd5e1"));
        mgc.setLineWidth(1.0);
        for (EdgeDto edge : edges) {
            NodeDto src = getNode(edge.getSource());
            NodeDto tgt = getNode(edge.getTarget());
            if (src != null && tgt != null) {
                mgc.strokeLine(src.getX() * scale + offX, src.getY() * scale + offY, tgt.getX() * scale + offX, tgt.getY() * scale + offY);
            }
        }

        for (NodeDto n : nodes) {
            Color c = getTypeColor(n.getType());
            mgc.setFill(c);
            double nx = n.getX() * scale + offX;
            double ny = n.getY() * scale + offY;
            int deg = getNodeDegree(n);
            double dotR = deg >= 3 ? 3.0 : 2.0;
            mgc.fillOval(nx - dotR, ny - dotR, dotR * 2, dotR * 2);
        }

        double viewMinX = -panX / zoom;
        double viewMinY = -panY / zoom;
        double viewMaxX = (canvas.getWidth() - panX) / zoom;
        double viewMaxY = (canvas.getHeight() - panY) / zoom;

        double rx = viewMinX * scale + offX;
        double ry = viewMinY * scale + offY;
        double rw = (viewMaxX - viewMinX) * scale;
        double rh = (viewMaxY - viewMinY) * scale;

        mgc.setStroke(Color.web("#38BDF8"));
        mgc.setLineWidth(1.5);
        mgc.strokeRect(Math.max(0, rx), Math.max(0, ry), Math.min(mw, rw), Math.min(mh, rh));
    }

    private NodeDto findNodeAt(double screenX, double screenY) {
        double worldX = (screenX - panX) / zoom;
        double worldY = (screenY - panY) / zoom;

        List<NodeDto> searchOrder = new ArrayList<>(nodes);
        searchOrder.sort((a, b) -> Integer.compare(getNodeDegree(b), getNodeDegree(a)));

        for (NodeDto n : searchOrder) {
            double dx = worldX - n.getX();
            double dy = worldY - n.getY();
            double r = getNodeRadius(n);
            if (dx * dx + dy * dy <= r * r) return n;
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
                if (dist < 12.0) return edge;
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

    public NodeDto getNode(String idOrName) {
        if (idOrName == null) return null;
        return nodeLookup.get(idOrName.toLowerCase(Locale.ROOT));
    }

    public int getNodeDegree(NodeDto n) {
        if (n == null) return 0;
        String k = n.getId() != null ? n.getId() : n.getName();
        return nodeDegreeMap.getOrDefault(k, 0);
    }

    private double getNodeRadius(NodeDto n) {
        int mentions = n.getMentions() != null ? n.getMentions() : 1;
        int degree = getNodeDegree(n);
        return Math.min(56.0, 36.0 + Math.min(12.0, degree * 2.5) + Math.log(mentions + 1) * 2.5);
    }

    private Color getTypeColor(String type) {
        if (type == null) return Color.web("#94A3B8");
        String t = type.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "PERSON" -> Color.web("#3B82F6");
            case "PHONE_NUMBER", "PHONE" -> Color.web("#06B6D4");
            case "DOCUMENT", "TRANSCRIPT" -> Color.web("#F59E0B");
            case "LOCATION" -> Color.web("#10B981");
            case "ORGANIZATION", "ORG" -> Color.web("#8B5CF6");
            case "MESSAGE", "MSG" -> Color.web("#0D9488");
            case "MEDICAL", "HOSPITAL", "HEALTH" -> Color.web("#F43F5E");
            case "EVENT" -> Color.web("#EF4444");
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
            case "ORGANIZATION", "ORG" -> "🏢";
            case "MESSAGE", "MSG" -> "💬";
            case "MEDICAL", "HOSPITAL", "HEALTH" -> "✚";
            case "EVENT" -> "⚡";
            default -> "●";
        };
    }

    public void setOnNodeSelected(Consumer<NodeDto> onNodeSelected) {
        this.onNodeSelected = onNodeSelected;
    }

    public void setOnEdgeSelected(Consumer<EdgeDto> onEdgeSelected) {
        this.onEdgeSelected = onEdgeSelected;
    }

    public void setOnSelectionCleared(Runnable onSelectionCleared) {
        this.onSelectionCleared = onSelectionCleared;
    }
}
