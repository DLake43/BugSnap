import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * BugSnap - a simple tool for UAT testers.
 *
 * NEW REPORT:  capture the screen, choose Pass / Fail, describe the bug, save.
 * ALL BUGS:    search, filter and sort every saved report; open one to mark it
 *              fixed and add responses.
 * REPORT SITE: the "Open report site" button starts a small web page that only
 *              your own computer can reach (localhost). It lists the same bugs and
 *              lets you mark them fixed and add responses from a browser.
 *
 * Each report is stored in its own folder (BUG-0001, BUG-0002 ...) inside the
 * save folder, so the app and the site always show the same data.
 *
 * Note: the class is deliberately not public, so the file can have any name.
 */
class BugSnap extends JFrame {

    // ---- Palette (dark, flat, one accent) ----
    private static final Color BG = new Color(0x292D33);
    private static final Color TOPBAR = new Color(0x22262C);
    private static final Color PANEL = new Color(0x32373F);
    private static final Color PANEL2 = new Color(0x3A4049);
    private static final Color INPUT = new Color(0x272B31);
    private static final Color BORDER = new Color(0x454B55);
    private static final Color BORDER2 = new Color(0x555D68);
    private static final Color TEXT = new Color(0xF0F2F5);
    private static final Color MUTED = new Color(0xB1B7C0);
    private static final Color DIM = new Color(0x858D98);
    private static final Color ACCENT = new Color(0x79B4FF);
    private static final Color ACCENT_HOVER = new Color(0xA0CAFF);
    private static final Color ON_ACCENT = new Color(0x182333);
    private static final Color SELECT_BG = new Color(0x414D60);
    private static final Color FAIL = new Color(0xF0616D);
    private static final Color PASS = new Color(0x4CC38A);
    private static final Color AMBER = new Color(0xE5A93D);

    // ---- Fonts: best available of each list ----
    private static final Font UI = pickFont(13f, "Segoe UI", "Inter", "Helvetica Neue", "Noto Sans", "DejaVu Sans", "Dialog");
    private static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    private static final Font MONO = pickFont(12f, "Cascadia Mono", "Consolas", "JetBrains Mono", "Menlo",
            "DejaVu Sans Mono", "Monospaced");

    private static Font pickFont(float size, String... families) {
        Set<String> have = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : families) {
            if (have.contains(f)) {
                return new Font(f, Font.PLAIN, 12).deriveFont(size);
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, 12).deriveFont(size);
    }

    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter SORT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String OPEN = "Open";
    private static final String FIXED = "Fixed";
    private static final String PASSED = "Passed";

    // ---- State ----
    private final BugStore store = new BugStore(new File(System.getProperty("user.home"), "bugsnap-reports"));
    private final SiteServer site = new SiteServer(store);
    private BufferedImage screenshot;
    private LocalDateTime capturedAt;

    // ---- Shell ----
    private final CardLayout pagesLayout = new CardLayout();
    private final JPanel pages = new JPanel(pagesLayout);
    private final NavTab navNew = new NavTab("New report");
    private final NavTab navAll = new NavTab("All bugs");

    // ---- New report page ----
    private final JComboBox<String> resultBox = new JComboBox<>(new String[]{"Pass", "Fail"});
    private final JTextArea detailsArea = new JTextArea();
    private final PreviewPanel preview = new PreviewPanel();
    private final JLabel folderLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final FlatButton captureButton = new FlatButton("Capture screenshot", FlatButton.PRIMARY);
    private final FlatButton saveButton = new FlatButton("Save report", FlatButton.PRIMARY);
    private final CardLayout detailsLayout = new CardLayout();
    private final JPanel detailsHolder = new JPanel(detailsLayout);

    // ---- All bugs page ----
    private final BugTableModel tableModel = new BugTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<BugTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTextField searchField = new JTextField() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(DIM);
                g2.setFont(UI);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("Search reference, details or responses", getInsets().left,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        }
    };
    private final JComboBox<String> statusFilter = new JComboBox<>(new String[]{"All statuses", OPEN, FIXED});
    private final JComboBox<String> resultFilter = new JComboBox<>(new String[]{"All results", "Fail", "Pass"});
    private final JLabel countLabel = new JLabel(" ");

    /** Applies the dark theme. Call once before creating any window. */
    static void installTheme() {
        UIManager.put("control", PANEL);
        UIManager.put("info", PANEL2);
        UIManager.put("nimbusBase", new Color(0x454B55));
        UIManager.put("nimbusBlueGrey", new Color(0x555D68));
        UIManager.put("nimbusLightBackground", INPUT);
        UIManager.put("text", TEXT);
        UIManager.put("nimbusSelectedText", TEXT);
        UIManager.put("nimbusSelectionBackground", SELECT_BG);
        UIManager.put("nimbusDisabledText", DIM);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // keep the default look and feel; the app paints most things itself
        }
    }

    public BugSnap() {
        super("BugSnap");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        buildUi();
        setSize(940, 760);
        setMinimumSize(new Dimension(780, 660));
        setLocationRelativeTo(null);
        updateFolderLabel();
        updateDetailsState();
        refreshTable();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                refreshTable();   // pick up changes made in the browser
            }
        });
    }

    // ==================================================================
    // UI construction
    // ==================================================================
    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        setContentPane(root);
        root.add(buildTopBar(), BorderLayout.NORTH);

        pages.setBackground(BG);
        pages.add(buildNewReportPage(), "new");
        pages.add(buildTrackerPage(), "all");
        root.add(pages, BorderLayout.CENTER);

        navNew.onClick(() -> showPage("new"));
        navAll.onClick(() -> showPage("all"));
        showPage("new");
    }

    private void showPage(String name) {
        pagesLayout.show(pages, name);
        navNew.setSelected("new".equals(name));
        navAll.setSelected("all".equals(name));
        if ("all".equals(name)) {
            refreshTable();
        }
    }

    private JComponent buildTopBar() {
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(TOPBAR);
        JPanel accentStrip = new JPanel();
        accentStrip.setBackground(ACCENT);
        accentStrip.setPreferredSize(new Dimension(1, 4));
        shell.add(accentStrip, BorderLayout.NORTH);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bar.setBackground(TOPBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

        JComponent brand = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int cy = getHeight() / 2;
                double cx = 30;

                // Line-art beetle: oval body, two eyes, six short curved legs.
                g2.setColor(ACCENT);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new java.awt.geom.Ellipse2D.Double(cx - 6, cy - 8, 12, 17));

                // Eyes (two dots near the top of the body)
                g2.fill(new java.awt.geom.Ellipse2D.Double(cx - 2.8 - 1.3, cy - 5.5, 2.6, 2.6));
                g2.fill(new java.awt.geom.Ellipse2D.Double(cx + 2.8 - 1.3, cy - 5.5, 2.6, 2.6));

                // Legs: 3 per side, smooth quadratic curves
                for (int side = -1; side <= 1; side += 2) {
                    java.awt.geom.Path2D.Double legs = new java.awt.geom.Path2D.Double();
                    legs.moveTo(cx + side * 5.4, cy - 3);
                    legs.quadTo(cx + side * 10, cy - 4, cx + side * 11.5, cy - 8);   // front leg
                    legs.moveTo(cx + side * 6.0, cy + 1);
                    legs.quadTo(cx + side * 10, cy - 1, cx + side * 12.5, cy + 1);   // middle leg
                    legs.moveTo(cx + side * 5.2, cy + 5);
                    legs.quadTo(cx + side * 10, cy + 5, cx + side * 11.5, cy + 9);   // back leg
                    g2.draw(legs);
                }

                g2.setColor(TEXT);
                g2.setFont(MONO.deriveFont(Font.BOLD, 14f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("BUGSNAP", 50, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        brand.setPreferredSize(new Dimension(168, 48));
        brand.setMaximumSize(new Dimension(168, 48));

        bar.add(brand);
        bar.add(navNew);
        bar.add(navAll);
        shell.add(bar, BorderLayout.CENTER);
        return shell;
    }

    // ------------------------------------------------------------------
    // New report page
    // ------------------------------------------------------------------
    private JComponent buildNewReportPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(BG);

        // ---- 01 capture ----
        Card captureCard = new Card(new BorderLayout(0, 12));
        JPanel captureTop = new JPanel(new BorderLayout());
        captureTop.setOpaque(false);
        captureTop.add(sectionTitle("01", "Capture"), BorderLayout.WEST);
        captureTop.add(captureButton, BorderLayout.EAST);
        captureCard.add(captureTop, BorderLayout.NORTH);
        captureCard.add(preview, BorderLayout.CENTER);

        // ---- 02 result ----
        Card resultCard = new Card(new BorderLayout(0, 12));
        JPanel resultTop = new JPanel(new BorderLayout());
        resultTop.setOpaque(false);
        resultTop.add(sectionTitle("02", "Result"), BorderLayout.WEST);
        styleCombo(resultBox, true);
        resultTop.add(resultBox, BorderLayout.EAST);
        resultCard.add(resultTop, BorderLayout.NORTH);

        styleTextArea(detailsArea, true);
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        flatScroll(detailsScroll);

        JPanel failPanel = new JPanel(new BorderLayout(0, 6));
        failPanel.setOpaque(false);
        JLabel failHint = new JLabel("What went wrong? Steps to reproduce, and what you expected.");
        failHint.setFont(UI.deriveFont(12f));
        failHint.setForeground(MUTED);
        failPanel.add(failHint, BorderLayout.NORTH);
        failPanel.add(detailsScroll, BorderLayout.CENTER);

        JLabel passLabel = new JLabel("Marked as pass. Nothing else to record.", SwingConstants.CENTER);
        passLabel.setFont(UI.deriveFont(12f));
        passLabel.setForeground(MUTED);

        detailsHolder.setOpaque(false);
        detailsHolder.add(failPanel, "fail");
        detailsHolder.add(passLabel, "pass");
        detailsHolder.setPreferredSize(new Dimension(100, 170));
        resultCard.add(detailsHolder, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(16, 20, 12, 20));
        content.add(captureCard, BorderLayout.CENTER);
        content.add(resultCard, BorderLayout.SOUTH);
        page.add(content, BorderLayout.CENTER);
        page.add(buildFooter(), BorderLayout.SOUTH);

        saveButton.setEnabled(false);
        captureButton.addActionListener(this::onCapture);
        saveButton.addActionListener(this::onSave);
        resultBox.addActionListener(e -> updateDetailsState());
        return page;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(TOPBAR);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(10, 20, 10, 20)));

        folderLabel.setFont(MONO.deriveFont(11f));
        folderLabel.setForeground(MUTED);
        statusLabel.setFont(MONO.deriveFont(Font.BOLD, 11f));
        statusLabel.setForeground(ACCENT);

        JLabel change = new JLabel("change");
        change.setFont(MONO.deriveFont(11f));
        change.setForeground(ACCENT);
        change.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        change.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                chooseFolder();
            }
        });

        JPanel pathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pathRow.setOpaque(false);
        pathRow.add(folderLabel);
        pathRow.add(change);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(pathRow);
        left.add(Box.createVerticalStrut(3));
        left.add(statusLabel);

        footer.add(left, BorderLayout.CENTER);
        footer.add(saveButton, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------
    // All bugs page
    // ------------------------------------------------------------------
    private JComponent buildTrackerPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBackground(BG);
        page.setBorder(new EmptyBorder(16, 20, 14, 20));

        // ---- Toolbar ----
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;

        styleTextField(searchField);
        searchField.setPreferredSize(new Dimension(200, 34));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        styleCombo(statusFilter, false);
        styleCombo(resultFilter, false);
        statusFilter.addActionListener(e -> applyFilter());
        resultFilter.addActionListener(e -> applyFilter());

        c.gridx = 0; c.weightx = 1;
        bar.add(searchField, c);
        c.gridx = 1; c.weightx = 0;
        bar.add(statusFilter, c);
        c.gridx = 2;
        bar.add(resultFilter, c);

        FlatButton siteButton = new FlatButton("Open report site", FlatButton.SECONDARY);
        siteButton.addActionListener(e -> openSite());
        c.gridx = 3; c.insets = new Insets(0, 0, 0, 0);
        bar.add(siteButton, c);
        page.add(bar, BorderLayout.NORTH);

        // ---- Table ----
        table.setRowSorter(sorter);
        table.setBackground(new Color(0x2E333A));   // plain Color (not a theme resource) so no zebra striping
        table.setForeground(TEXT);
        table.setFont(UI);
        table.setRowHeight(32);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setGridColor(BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionBackground(SELECT_BG);
        table.setSelectionForeground(TEXT);
        table.setFillsViewportHeight(true);

        JTableHeader th = table.getTableHeader();
        th.setDefaultRenderer(new HeaderRenderer());
        th.setReorderingAllowed(false);
        th.setBackground(PANEL);
        th.setPreferredSize(new Dimension(100, 30));

        int[] widths = {90, 130, 80, 90, 340, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new CellRenderer(true, false));
        table.getColumnModel().getColumn(1).setCellRenderer(new CellRenderer(true, false));
        table.getColumnModel().getColumn(2).setCellRenderer(new CellRenderer(false, true));
        table.getColumnModel().getColumn(3).setCellRenderer(new CellRenderer(false, true));
        table.getColumnModel().getColumn(4).setCellRenderer(new CellRenderer(false, false));
        table.getColumnModel().getColumn(5).setCellRenderer(new CellRenderer(true, false));

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow >= 0) {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        openDetail(tableModel.get(modelRow).id);
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        flatScroll(scroll);
        scroll.getViewport().setBackground(new Color(0x2E333A));
        JPanel corner = new JPanel();
        corner.setBackground(PANEL);
        scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        page.add(scroll, BorderLayout.CENTER);

        // ---- Bottom row ----
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        countLabel.setFont(MONO.deriveFont(11f));
        countLabel.setForeground(MUTED);
        JLabel hint = new JLabel("click a heading to sort / double-click a row to open");
        hint.setFont(MONO.deriveFont(11f));
        hint.setForeground(DIM);
        bottom.add(countLabel, BorderLayout.WEST);
        bottom.add(hint, BorderLayout.EAST);
        page.add(bottom, BorderLayout.SOUTH);
        return page;
    }

    private void applyFilter() {
        final String q = searchField.getText().trim().toLowerCase();
        final String status = (String) statusFilter.getSelectedItem();
        final String result = (String) resultFilter.getSelectedItem();
        sorter.setRowFilter(new javax.swing.RowFilter<BugTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends BugTableModel, ? extends Integer> entry) {
                Bug b = entry.getModel().get(entry.getIdentifier());
                if (!"All statuses".equals(status) && !status.equals(b.status)) {
                    return false;
                }
                if (!"All results".equals(result) && !result.equals(b.result)) {
                    return false;
                }
                return q.isEmpty() || b.searchText().contains(q);
            }
        });
    }

    private void refreshTable() {
        List<Bug> all = store.loadAll();
        tableModel.setBugs(all);
        int open = 0, fixed = 0, passed = 0;
        for (Bug b : all) {
            if (!b.isFail()) passed++;
            else if (FIXED.equals(b.status)) fixed++;
            else open++;
        }
        countLabel.setText(all.isEmpty() ? "no reports yet"
                : all.size() + " reports  /  " + open + " open  /  " + fixed + " fixed  /  " + passed + " passed");
    }

    private void openSite() {
        try {
            String url = site.start();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                JOptionPane.showMessageDialog(this, "Open this address in your browser:\n" + url,
                        "BugSnap", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not start the report site:\n" + ex.getMessage(),
                    "BugSnap", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Bug detail dialog (view, mark fixed, respond)
    // ------------------------------------------------------------------
    private void openDetail(String id) {
        final Bug bug = store.load(id);
        if (bug == null) {
            refreshTable();
            return;
        }
        final JDialog d = new JDialog(this, bug.id, true);
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        d.setContentPane(root);

        // ---- Top line ----
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        top.setOpaque(false);
        JLabel title = new JLabel(bug.id);
        title.setFont(MONO.deriveFont(Font.BOLD, 20f));
        title.setForeground(TEXT);
        top.add(title);
        top.add(new Tag(bug.result, bug.isFail() ? FAIL : PASS));
        if (bug.isFail()) {
            top.add(new Tag(bug.status, FIXED.equals(bug.status) ? PASS : AMBER));
        }
        JLabel when = new JLabel(bug.created.format(DISPLAY_TS));
        when.setFont(MONO.deriveFont(12f));
        when.setForeground(MUTED);
        top.add(when);
        root.add(top, BorderLayout.NORTH);

        // ---- Left: screenshot ----
        PreviewPanel shot = new PreviewPanel();
        try {
            File f = store.screenshot(bug.id);
            if (f.isFile()) {
                shot.setImage(ImageIO.read(f));
            }
        } catch (IOException ignored) {
            // leave placeholder
        }
        Card shotCard = new Card(new BorderLayout());
        shotCard.add(shot, BorderLayout.CENTER);
        shotCard.setPreferredSize(new Dimension(470, 100));

        // ---- Right: details + responses + new response ----
        JTextArea detailsView = readOnlyArea(bug.isFail() ? bug.details : "Marked as pass. No details.");
        StringBuilder sb = new StringBuilder();
        for (Response r : bug.responses) {
            sb.append("[").append(r.time.format(DISPLAY_TS)).append("]\n").append(r.text).append("\n\n");
        }
        JTextArea responsesView = readOnlyArea(bug.responses.isEmpty() ? "No responses yet." : sb.toString().trim());

        JPanel right = new JPanel(new GridLayout(0, 1, 0, 10));
        right.setOpaque(false);
        right.add(section("DETAILS", new JScrollPane(detailsView)));
        right.add(section("RESPONSES", new JScrollPane(responsesView)));

        JPanel rightWrap = new JPanel(new BorderLayout(0, 10));
        rightWrap.setOpaque(false);
        rightWrap.add(right, BorderLayout.CENTER);

        if (bug.isFail()) {
            final JTextArea reply = new JTextArea();
            styleTextArea(reply, false);
            right.add(section("ADD A RESPONSE", new JScrollPane(reply)));

            final boolean isFixed = FIXED.equals(bug.status);
            FlatButton toggle = new FlatButton(isFixed ? "Reopen" : "Mark as fixed", FlatButton.SECONDARY);
            FlatButton respond = new FlatButton("Add response", FlatButton.SECONDARY);
            FlatButton resolve = new FlatButton("Respond & mark fixed", FlatButton.PRIMARY);

            toggle.addActionListener(e -> {
                try {
                    store.setStatus(bug.id, isFixed ? OPEN : FIXED);
                    reopen(d, bug.id);
                } catch (IOException ex) {
                    showError(d, ex);
                }
            });
            respond.addActionListener(e -> submitResponse(d, bug.id, reply, false));
            resolve.addActionListener(e -> submitResponse(d, bug.id, reply, true));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(toggle);
            buttons.add(respond);
            buttons.add(resolve);
            rightWrap.add(buttons, BorderLayout.SOUTH);
        }

        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.add(shotCard, BorderLayout.WEST);
        body.add(rightWrap, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        d.setSize(1060, 640);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private void submitResponse(JDialog d, String id, JTextArea reply, boolean markFixed) {
        String text = reply.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(d, "Please type a response first.", "BugSnap", JOptionPane.WARNING_MESSAGE);
            reply.requestFocusInWindow();
            return;
        }
        try {
            store.addResponse(id, text, markFixed);
            reopen(d, id);
        } catch (IOException ex) {
            showError(d, ex);
        }
    }

    /** Closes the dialog, refreshes the table and shows the bug again with the new data. */
    private void reopen(JDialog d, String id) {
        d.dispose();
        refreshTable();
        SwingUtilities.invokeLater(() -> openDetail(id));
    }

    private void showError(Component parent, Exception ex) {
        JOptionPane.showMessageDialog(parent, "Could not save the change:\n" + ex.getMessage(),
                "BugSnap", JOptionPane.ERROR_MESSAGE);
    }

    private JTextArea readOnlyArea(String text) {
        JTextArea a = new JTextArea(text);
        styleTextArea(a, false);
        a.setEditable(false);
        a.setCaretPosition(0);
        return a;
    }

    private JComponent section(String title, JScrollPane body) {
        flatScroll(body);
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(title);
        l.setFont(MONO.deriveFont(Font.BOLD, 11f));
        l.setForeground(MUTED);
        p.add(l, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------
    // Shared UI helpers
    // ------------------------------------------------------------------
    private JComponent sectionTitle(String number, String text) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        JLabel n = new JLabel(number);
        n.setBorder(new EmptyBorder(0, 0, 0, 10));
        n.setFont(MONO.deriveFont(Font.BOLD, 12f));
        n.setForeground(ACCENT);
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(MONO.deriveFont(Font.BOLD, 12f));
        label.setForeground(TEXT);
        p.add(n);
        p.add(label);
        return p;
    }

    private static void styleTextField(JTextField f) {
        f.setFont(UI);
        f.setForeground(TEXT);
        f.setBackground(INPUT);
        f.setCaretColor(TEXT);
        f.setSelectionColor(SELECT_BG);
        f.setSelectedTextColor(TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER2), new EmptyBorder(0, 10, 0, 10)));
    }

    private static void styleTextArea(JTextArea a, boolean editable) {
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(MONO);
        a.setForeground(TEXT);
        a.setBackground(INPUT);
        a.setCaretColor(TEXT);
        a.setSelectionColor(SELECT_BG);
        a.setSelectedTextColor(TEXT);
        a.setEditable(true);
        a.setBorder(new EmptyBorder(8, 10, 8, 10));
    }

    private static void flatScroll(JScrollPane sp) {
        sp.setBorder(BorderFactory.createLineBorder(BORDER2));
        sp.setBackground(INPUT);
        sp.getViewport().setBackground(INPUT);
        sp.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        sp.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
    }

    private void styleCombo(JComboBox<String> box, boolean withDots) {
        box.setUI(new FlatComboUI());
        box.setFont(withDots ? UI_BOLD : UI);
        box.setForeground(TEXT);
        box.setBackground(INPUT);
        box.setBorder(BorderFactory.createLineBorder(BORDER2));
        box.setPreferredSize(new Dimension(withDots ? 120 : 130, 34));
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                l.setOpaque(true);
                l.setBackground(index >= 0 && isSelected ? SELECT_BG : INPUT);
                l.setForeground(TEXT);
                l.setBorder(new EmptyBorder(5, 10, 5, 10));
                if (withDots) {
                    l.setIcon(new DotIcon("Fail".equals(value) ? FAIL : PASS));
                    l.setIconTextGap(8);
                } else {
                    l.setIcon(null);
                }
                return l;
            }
        });
    }

    private void updateDetailsState() {
        boolean fail = "Fail".equals(resultBox.getSelectedItem());
        detailsLayout.show(detailsHolder, fail ? "fail" : "pass");
        if (fail) {
            detailsArea.requestFocusInWindow();
        }
    }

    private void updateFolderLabel() {
        folderLabel.setText("saving to  " + store.getDir().getAbsolutePath());
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(store.getDir().getParentFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            store.setDir(chooser.getSelectedFile());
            updateFolderLabel();
            refreshTable();
        }
    }

    // ==================================================================
    // Capture and save
    // ==================================================================
    private void onCapture(ActionEvent e) {
        captureButton.setEnabled(false);
        setState(Frame.ICONIFIED);   // get BugSnap out of the way

        // Give the window manager time to minimise before grabbing the screen
        javax.swing.Timer timer = new javax.swing.Timer(900, ev -> {
            try {
                Rectangle all = allScreensBounds();
                screenshot = new Robot().createScreenCapture(all);
                capturedAt = LocalDateTime.now();
            } catch (AWTException ex) {
                screenshot = null;
                JOptionPane.showMessageDialog(this, "Could not capture the screen:\n" + ex.getMessage(),
                        "BugSnap", JOptionPane.ERROR_MESSAGE);
            } finally {
                setState(Frame.NORMAL);
                toFront();
                captureButton.setEnabled(true);
            }
            showPreview();
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** Union of all monitors so multi-screen setups are captured fully. */
    private static Rectangle allScreensBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds;
    }

    private void showPreview() {
        if (screenshot == null) {
            return;
        }
        preview.setImage(screenshot);
        resultBox.setSelectedItem("Fail");      // default to Fail after a capture
        saveButton.setEnabled(true);
        statusLabel.setText("captured " + capturedAt.format(DISPLAY_TS));
    }

    private void onSave(ActionEvent e) {
        if (screenshot == null) {
            return;
        }
        String result = (String) resultBox.getSelectedItem();
        String details = detailsArea.getText().trim();

        if ("Fail".equals(result) && details.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please describe the bug before saving a Fail report.",
                    "BugSnap", JOptionPane.WARNING_MESSAGE);
            detailsArea.requestFocusInWindow();
            return;
        }

        try {
            Bug saved = store.create(result, "Fail".equals(result) ? details : "", capturedAt, screenshot);
            statusLabel.setText("saved as " + saved.id);
            resetForm();
            refreshTable();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the report:\n" + ex.getMessage(),
                    "BugSnap", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetForm() {
        screenshot = null;
        preview.setImage(null);
        resultBox.setSelectedItem("Fail");      // keep Fail as the default
        detailsArea.setText("");
        saveButton.setEnabled(false);
    }

    // ==================================================================
    // Data model
    // ==================================================================
    static class Response {
        final LocalDateTime time;
        final String text;

        Response(LocalDateTime time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    static class Bug {
        String id;
        LocalDateTime created;
        String result;     // "Pass" or "Fail"
        String status;     // Open / Fixed (Fail) or Passed (Pass)
        String details = "";
        List<Response> responses = new ArrayList<>();

        boolean isFail() {
            return "Fail".equals(result);
        }

        String summary() {
            if (!isFail() || details.isEmpty()) {
                return "";
            }
            String line = details.split("\\R", 2)[0];
            return line.length() > 90 ? line.substring(0, 87) + "..." : line;
        }

        String searchText() {
            StringBuilder sb = new StringBuilder(id).append(' ').append(details);
            for (Response r : responses) {
                sb.append(' ').append(r.text);
            }
            return sb.toString().toLowerCase();
        }
    }

    /** Stores each report in its own folder: BUG-0001/bug.properties + screenshot.png. */
    static class BugStore {
        private File dir;

        BugStore(File dir) {
            this.dir = dir;
        }

        synchronized File getDir() {
            return dir;
        }

        synchronized void setDir(File d) {
            this.dir = d;
        }

        static boolean validId(String id) {
            return id != null && id.matches("BUG-\\d{4,9}");
        }

        synchronized Bug create(String result, String details, LocalDateTime created, BufferedImage img)
                throws IOException {
            Files.createDirectories(dir.toPath());
            int next = 1;
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isDirectory() && validId(k.getName())) {
                        next = Math.max(next, Integer.parseInt(k.getName().substring(4)) + 1);
                    }
                }
            }
            Bug b = new Bug();
            b.id = String.format("BUG-%04d", next);
            b.created = created;
            b.result = result;
            b.details = details;
            b.status = "Fail".equals(result) ? OPEN : PASSED;

            File bugDir = new File(dir, b.id);
            Files.createDirectories(bugDir.toPath());
            ImageIO.write(img, "png", new File(bugDir, "screenshot.png"));
            write(b);
            return b;
        }

        synchronized List<Bug> loadAll() {
            List<Bug> list = new ArrayList<>();
            File[] kids = dir.listFiles();
            if (kids == null) {
                return list;
            }
            for (File k : kids) {
                if (k.isDirectory() && validId(k.getName())) {
                    Bug b = read(k.getName());
                    if (b != null) {
                        list.add(b);
                    }
                }
            }
            list.sort(Comparator.comparing((Bug b) -> b.id));
            return list;
        }

        synchronized Bug load(String id) {
            return validId(id) ? read(id) : null;
        }

        synchronized File screenshot(String id) {
            return new File(new File(dir, id), "screenshot.png");
        }

        synchronized void setStatus(String id, String status) throws IOException {
            Bug b = load(id);
            if (b == null || !b.isFail() || !(OPEN.equals(status) || FIXED.equals(status))) {
                return;
            }
            b.status = status;
            write(b);
        }

        synchronized void addResponse(String id, String text, boolean markFixed) throws IOException {
            Bug b = load(id);
            if (b == null || !b.isFail() || text == null || text.trim().isEmpty()) {
                return;
            }
            b.responses.add(new Response(LocalDateTime.now(), text.trim()));
            if (markFixed) {
                b.status = FIXED;
            }
            write(b);
        }

        private Bug read(String id) {
            File f = new File(new File(dir, id), "bug.properties");
            if (!f.isFile()) {
                return null;
            }
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(r);
                Bug b = new Bug();
                b.id = id;
                b.created = LocalDateTime.parse(p.getProperty("created"));
                b.result = p.getProperty("result", "Fail");
                b.status = p.getProperty("status", b.isFail() ? OPEN : PASSED);
                b.details = p.getProperty("details", "");
                int n = Integer.parseInt(p.getProperty("response.count", "0"));
                for (int i = 0; i < n; i++) {
                    b.responses.add(new Response(
                            LocalDateTime.parse(p.getProperty("response." + i + ".time")),
                            p.getProperty("response." + i + ".text", "")));
                }
                return b;
            } catch (Exception e) {
                return null;   // skip corrupt records
            }
        }

        private void write(Bug b) throws IOException {
            Properties p = new Properties();
            p.setProperty("created", b.created.toString());
            p.setProperty("result", b.result);
            p.setProperty("status", b.status);
            p.setProperty("details", b.details);
            p.setProperty("response.count", String.valueOf(b.responses.size()));
            for (int i = 0; i < b.responses.size(); i++) {
                p.setProperty("response." + i + ".time", b.responses.get(i).time.toString());
                p.setProperty("response." + i + ".text", b.responses.get(i).text);
            }
            File bugDir = new File(dir, b.id);
            File tmp = new File(bugDir, "bug.properties.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                p.store(w, "BugSnap record");
            }
            Files.move(tmp.toPath(), new File(bugDir, "bug.properties").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================================================================
    // Table model + renderers
    // ==================================================================
    static class BugTableModel extends AbstractTableModel {
        private final String[] cols = {"Ref", "Date", "Result", "Status", "Summary", "Replies"};
        private List<Bug> bugs = new ArrayList<>();

        void setBugs(List<Bug> list) {
            this.bugs = list;
            fireTableDataChanged();
        }

        Bug get(int row) {
            return bugs.get(row);
        }

        public int getRowCount() {
            return bugs.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 5 ? Integer.class : String.class;
        }

        public Object getValueAt(int row, int col) {
            Bug b = bugs.get(row);
            switch (col) {
                case 0: return b.id;
                case 1: return b.created.format(SORT_TS);
                case 2: return b.result;
                case 3: return b.status;
                case 4: return b.summary();
                default: return b.responses.size();
            }
        }
    }

    /** Dark cell renderer; optionally monospaced, optionally with a coloured status dot. */
    static class CellRenderer extends DefaultTableCellRenderer {
        private final boolean mono;
        private final boolean status;

        CellRenderer(boolean mono, boolean status) {
            this.mono = mono;
            this.status = status;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            setOpaque(true);
            setBackground(sel ? SELECT_BG : new Color(0x2E333A));
            setForeground(mono ? MUTED : TEXT);
            setFont(mono ? MONO.deriveFont(12f) : UI);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setIcon(null);
            if (col == 0) {
                setForeground(TEXT);
            }
            if (status) {
                String v = String.valueOf(value);
                Color c = TEXT;
                if ("Fail".equals(v)) c = FAIL;
                else if ("Pass".equals(v) || FIXED.equals(v) || PASSED.equals(v)) c = PASS;
                else if (OPEN.equals(v)) c = AMBER;
                setForeground(c);
                setIcon(new DotIcon(c));
                setIconTextGap(8);
            }
            return this;
        }
    }

    /** Small monospaced uppercase column headings with a sort arrow. */
    static class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, value, false, false, row, col);
            String text = String.valueOf(value).toUpperCase();
            if (t.getRowSorter() != null) {
                for (RowSorter.SortKey k : t.getRowSorter().getSortKeys()) {
                    if (k.getColumn() == t.convertColumnIndexToModel(col)
                            && k.getSortOrder() != SortOrder.UNSORTED) {
                        text += k.getSortOrder() == SortOrder.ASCENDING ? "  ▲" : "  ▼";
                    }
                }
            }
            setText(text);
            setOpaque(true);
            setBackground(PANEL);
            setForeground(MUTED);
            setFont(MONO.deriveFont(Font.BOLD, 11f));
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER2), new EmptyBorder(0, 12, 0, 12)));
            return this;
        }
    }

    // ==================================================================
    // Local report site (http://127.0.0.1 only)
    // ==================================================================
    static class SiteServer {
        private final BugStore store;
        private HttpServer server;
        private int port;
        private final String token;

        SiteServer(BugStore store) {
            this.store = store;
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            this.token = sb.toString();
        }

        synchronized String start() throws IOException {
            if (server == null) {
                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                HttpServer s;
                try {
                    s = HttpServer.create(new InetSocketAddress(loopback, 8765), 0);
                } catch (BindException busy) {
                    s = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
                }
                s.createContext("/", this::handle);
                s.setExecutor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "bugsnap-site");
                    t.setDaemon(true);
                    return t;
                }));
                s.start();
                server = s;
                port = s.getAddress().getPort();
            }
            return "http://127.0.0.1:" + port + "/";
        }

        private void handle(HttpExchange ex) throws IOException {
            try {
                if (!hostOk(ex)) {
                    sendText(ex, 403, "Forbidden");
                    return;
                }
                String path = ex.getRequestURI().getPath();
                boolean post = "POST".equals(ex.getRequestMethod());
                Map<String, String> query = parseForm(ex.getRequestURI().getRawQuery());

                if (post) {
                    if (!originOk(ex)) {
                        sendText(ex, 403, "Forbidden");
                        return;
                    }
                    Map<String, String> form = parseForm(readBody(ex));
                    if (!token.equals(form.get("token"))) {
                        sendText(ex, 403, "Bad token");
                        return;
                    }
                    handlePost(ex, path, form);
                } else if ("GET".equals(ex.getRequestMethod())) {
                    switch (path) {
                        case "/": handleList(ex, query); break;
                        case "/bug": handleDetail(ex, query.get("id")); break;
                        case "/shot": handleShot(ex, query.get("id")); break;
                        default: sendText(ex, 404, "Not found");
                    }
                } else {
                    sendText(ex, 405, "Method not allowed");
                }
            } catch (Exception e) {
                sendText(ex, 500, "Something went wrong");
            } finally {
                ex.close();
            }
        }

        // ---- Request checks ----
        private boolean hostOk(HttpExchange ex) {
            String host = ex.getRequestHeaders().getFirst("Host");
            return ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host);
        }

        private boolean originOk(HttpExchange ex) {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            return origin == null || ("http://127.0.0.1:" + port).equals(origin)
                    || ("http://localhost:" + port).equals(origin);
        }

        private String readBody(HttpExchange ex) throws IOException {
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1_000_000) {
                        throw new IOException("Request too large");
                    }
                }
                return out.toString("UTF-8");
            }
        }

        private static Map<String, String> parseForm(String s) {
            Map<String, String> m = new HashMap<>();
            if (s == null || s.isEmpty()) {
                return m;
            }
            for (String pair : s.split("&")) {
                int i = pair.indexOf('=');
                try {
                    String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
                    String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
                    m.put(k, v);
                } catch (Exception ignored) {
                    // skip malformed pair
                }
            }
            return m;
        }

        // ---- Handlers ----
        private void handlePost(HttpExchange ex, String path, Map<String, String> form) throws IOException {
            String id = form.get("id");
            if (!BugStore.validId(id) || store.load(id) == null) {
                sendText(ex, 404, "Not found");
                return;
            }
            if ("/status".equals(path)) {
                store.setStatus(id, form.get("status"));
            } else if ("/respond".equals(path)) {
                store.addResponse(id, form.get("text"), "resolve".equals(form.get("action")));
            } else {
                sendText(ex, 404, "Not found");
                return;
            }
            ex.getResponseHeaders().set("Location", "/bug?id=" + id);
            ex.sendResponseHeaders(303, -1);
        }

        private void handleShot(HttpExchange ex, String id) throws IOException {
            if (!BugStore.validId(id)) {
                sendText(ex, 404, "Not found");
                return;
            }
            File f = store.screenshot(id);
            if (!f.isFile()) {
                sendText(ex, 404, "Not found");
                return;
            }
            byte[] data = Files.readAllBytes(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }

        private void handleList(HttpExchange ex, Map<String, String> q) throws IOException {
            List<Bug> all = store.loadAll();
            String search = q.getOrDefault("q", "").trim();
            String result = q.getOrDefault("result", "All");
            String status = q.getOrDefault("status", "All");
            String sort = q.getOrDefault("sort", "date");
            boolean asc = "asc".equals(q.getOrDefault("dir", "desc"));

            int open = 0, fixed = 0, passed = 0;
            for (Bug b : all) {
                if (!b.isFail()) passed++;
                else if (FIXED.equals(b.status)) fixed++;
                else open++;
            }

            List<Bug> rows = new ArrayList<>();
            String needle = search.toLowerCase();
            for (Bug b : all) {
                if (!"All".equals(result) && !result.equals(b.result)) continue;
                if (!"All".equals(status) && !status.equals(b.status)) continue;
                if (!needle.isEmpty() && !b.searchText().contains(needle)) continue;
                rows.add(b);
            }
            Comparator<Bug> cmp;
            switch (sort) {
                case "ref": cmp = Comparator.comparing((Bug b) -> b.id); break;
                case "result": cmp = Comparator.comparing((Bug b) -> b.result); break;
                case "status": cmp = Comparator.comparing((Bug b) -> b.status); break;
                case "replies": cmp = Comparator.comparingInt((Bug b) -> b.responses.size()); break;
                default: sort = "date"; cmp = Comparator.comparing((Bug b) -> b.created);
            }
            cmp = cmp.thenComparing((Bug b) -> b.id);
            rows.sort(asc ? cmp : cmp.reversed());

            StringBuilder h = new StringBuilder();
            h.append("<div class=\"stats\">")
                    .append(stat("Total", all.size(), ""))
                    .append(stat("Open", open, "open"))
                    .append(stat("Fixed", fixed, "fixed"))
                    .append(stat("Passed", passed, "pass"))
                    .append("</div>");

            h.append("<form class=\"filters\" method=\"get\" action=\"/\">")
                    .append("<input type=\"hidden\" name=\"sort\" value=\"").append(esc(sort)).append("\">")
                    .append("<input type=\"hidden\" name=\"dir\" value=\"").append(asc ? "asc" : "desc").append("\">")
                    .append("<input type=\"search\" name=\"q\" placeholder=\"Search reference, details or responses\" value=\"")
                    .append(esc(search)).append("\">")
                    .append(select("status", status, "All", "Open", "Fixed"))
                    .append(select("result", result, "All", "Fail", "Pass"))
                    .append("<button type=\"submit\" class=\"primary\">Apply</button>")
                    .append("<a class=\"reset\" href=\"/\">Reset</a></form>");

            h.append("<div class=\"tablewrap\"><table><thead><tr>");
            String[][] heads = {{"ref", "Ref"}, {"date", "Date"}, {"result", "Result"},
                    {"status", "Status"}, {"", "Summary"}, {"replies", "Replies"}};
            for (String[] hd : heads) {
                if (hd[0].isEmpty()) {
                    h.append("<th>").append(hd[1]).append("</th>");
                    continue;
                }
                boolean current = hd[0].equals(sort);
                String nextDir = current && asc ? "desc" : "asc";
                String arrow = current ? (asc ? " &#9650;" : " &#9660;") : "";
                h.append("<th><a href=\"/?sort=").append(hd[0]).append("&amp;dir=").append(nextDir)
                        .append("&amp;q=").append(enc(search))
                        .append("&amp;status=").append(enc(status))
                        .append("&amp;result=").append(enc(result)).append("\">")
                        .append(hd[1]).append(arrow).append("</a></th>");
            }
            h.append("</tr></thead><tbody>");
            if (rows.isEmpty()) {
                h.append("<tr><td colspan=\"6\" class=\"empty\">No reports match.</td></tr>");
            }
            for (Bug b : rows) {
                String link = "/bug?id=" + b.id;
                h.append("<tr><td class=\"mono\"><a class=\"ref\" href=\"").append(link).append("\">")
                        .append(b.id).append("</a></td>")
                        .append("<td class=\"mono muted\">").append(esc(b.created.format(SORT_TS))).append("</td>")
                        .append("<td>").append(tag(b.result)).append("</td>")
                        .append("<td>").append(b.isFail() ? tag(b.status) : "<span class=\"muted\">&ndash;</span>")
                        .append("</td>")
                        .append("<td><a class=\"plain\" href=\"").append(link).append("\">")
                        .append(esc(b.summary())).append("</a></td>")
                        .append("<td class=\"mono muted\">").append(b.responses.size()).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
            sendHtml(ex, 200, page("All bugs", h.toString()));
        }

        private void handleDetail(HttpExchange ex, String id) throws IOException {
            Bug b = BugStore.validId(id) ? store.load(id) : null;
            if (b == null) {
                sendText(ex, 404, "Not found");
                return;
            }
            StringBuilder h = new StringBuilder();
            h.append("<p class=\"back\"><a href=\"/\">&larr; All bugs</a></p>");
            h.append("<div class=\"panel head\"><div class=\"titlerow\"><h2 class=\"mono\">").append(b.id).append("</h2>")
                    .append(tag(b.result));
            if (b.isFail()) {
                h.append(tag(b.status));
            }
            h.append("<span class=\"mono muted\">").append(esc(b.created.format(DISPLAY_TS))).append("</span></div>");

            if (b.isFail()) {
                boolean isFixed = FIXED.equals(b.status);
                h.append("<form method=\"post\" action=\"/status\" class=\"inline\">")
                        .append(hidden("token", token)).append(hidden("id", b.id))
                        .append(hidden("status", isFixed ? OPEN : FIXED))
                        .append("<button type=\"submit\"").append(isFixed ? "" : " class=\"good\"").append(">")
                        .append(isFixed ? "Reopen bug" : "Mark as fixed").append("</button></form>");
            }
            h.append("</div>");

            if (b.isFail()) {
                h.append("<div class=\"panel\"><h3>Details</h3><div class=\"details\">")
                        .append(esc(b.details)).append("</div></div>");
            }

            h.append("<div class=\"panel\"><h3>Screenshot</h3><a href=\"/shot?id=").append(b.id)
                    .append("\" target=\"_blank\"><img alt=\"Screenshot\" src=\"/shot?id=").append(b.id)
                    .append("\"></a></div>");

            if (b.isFail()) {
                h.append("<div class=\"panel\"><h3>Responses</h3>");
                if (b.responses.isEmpty()) {
                    h.append("<p class=\"muted\">No responses yet.</p>");
                }
                for (Response r : b.responses) {
                    h.append("<div class=\"resp\"><div class=\"mono muted\">")
                            .append(esc(r.time.format(DISPLAY_TS))).append("</div><div class=\"details\">")
                            .append(esc(r.text)).append("</div></div>");
                }
                h.append("<form method=\"post\" action=\"/respond\">")
                        .append(hidden("token", token)).append(hidden("id", b.id))
                        .append("<textarea name=\"text\" rows=\"4\" required placeholder=\"Write a response, e.g. what was changed and where\"></textarea>")
                        .append("<div class=\"btnrow\">")
                        .append("<button type=\"submit\" name=\"action\" value=\"respond\">Add response</button>")
                        .append("<button type=\"submit\" name=\"action\" value=\"resolve\" class=\"primary\">Respond &amp; mark fixed</button>")
                        .append("</div></form></div>");
            }
            sendHtml(ex, 200, page(b.id, h.toString()));
        }

        // ---- HTML helpers ----
        private static String hidden(String name, String value) {
            return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + esc(value) + "\">";
        }

        private static String stat(String label, int n, String cls) {
            return "<div class=\"stat " + cls + "\"><div class=\"n\">" + n + "</div><div class=\"l\">"
                    + label + "</div></div>";
        }

        private static String tag(String text) {
            String cls;
            switch (text) {
                case "Fail": cls = "fail"; break;
                case OPEN: cls = "open"; break;
                default: cls = "pass";
            }
            return "<span class=\"tag " + cls + "\">" + esc(text) + "</span>";
        }

        private static String select(String name, String current, String... options) {
            String allLabel = "status".equals(name) ? "All statuses" : "All results";
            StringBuilder sb = new StringBuilder("<select name=\"" + name + "\">");
            for (String o : options) {
                sb.append("<option value=\"").append(esc(o)).append("\"")
                        .append(o.equals(current) ? " selected" : "").append(">")
                        .append("All".equals(o) ? allLabel : esc(o))
                        .append("</option>");
            }
            return sb.append("</select>").toString();
        }

        private static String enc(String s) {
            try {
                return URLEncoder.encode(s, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        private static String page(String title, String body) {
            return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<meta name=\"color-scheme\" content=\"dark\">"
                    + "<title>" + esc(title) + " - BugSnap</title><style>"
                    + ":root{--bg:#292d33;--top:#22262c;--panel:#32373f;--hover:#3a4049;--line:#454b55;--line2:#555d68;"
                    + "--text:#f0f2f5;--muted:#b1b7c0;--dim:#858d98;--accent:#79b4ff;--fail:#f0616d;--pass:#4cc38a;--amber:#e5a93d}"
                    + "*{box-sizing:border-box}"
                    + "body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 'Segoe UI',Inter,system-ui,sans-serif}"
                    + ".mono{font-family:ui-monospace,'Cascadia Mono',Consolas,Menlo,'DejaVu Sans Mono',monospace}"
                    + ".accentbar{height:4px;background:var(--accent)}"
                    + ".top{background:var(--top);border-bottom:1px solid var(--line);height:48px;display:flex;"
                    + "align-items:center;justify-content:space-between;padding:0 28px}"
                    + ".brand{font-family:ui-monospace,'Cascadia Mono',Consolas,Menlo,monospace;font-weight:700;"
                    + "letter-spacing:.12em;color:var(--text);text-decoration:none;font-size:14px}"
                    + ".brand:before{content:'●';display:inline-flex;align-items:center;justify-content:center;"
                    + "width:20px;height:20px;background:var(--accent);color:#182333;border-radius:6px;margin-right:12px;font-size:12px}"
                    + ".local{font-family:ui-monospace,Consolas,monospace;font-size:11px;color:var(--dim)}"
                    + ".wrap{max-width:1060px;margin:24px auto;padding:0 24px}"
                    + ".panel{background:var(--panel);border:1px solid var(--line);border-radius:4px;padding:16px 20px;margin-bottom:14px}"
                    + ".panel h3{margin:0 0 12px;font:700 11px ui-monospace,Consolas,monospace;letter-spacing:.1em;"
                    + "text-transform:uppercase;color:var(--muted)}"
                    + ".head{display:flex;justify-content:space-between;align-items:center;gap:16px}"
                    + ".titlerow{display:flex;gap:12px;align-items:center}.titlerow h2{margin:0;font-size:20px}"
                    + ".muted{color:var(--muted);font-size:13px}.back{margin:0 0 14px;font-size:13px}"
                    + "a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}"
                    + "a.plain{color:var(--text)}a.ref{color:var(--text);font-weight:600}"
                    + ".tag{display:inline-block;font:700 11px ui-monospace,Consolas,monospace;letter-spacing:.06em;"
                    + "text-transform:uppercase;padding:2px 8px;border:1px solid;border-radius:3px}"
                    + ".tag.fail{color:var(--fail);border-color:#6b2f36}.tag.pass{color:var(--pass);border-color:#26563f}"
                    + ".tag.open{color:var(--amber);border-color:#66501f}"
                    + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:14px}"
                    + ".stat{background:var(--panel);border:1px solid var(--line);border-radius:4px;padding:12px 16px}"
                    + ".stat .n{font:700 26px ui-monospace,Consolas,monospace}"
                    + ".stat .l{font:11px ui-monospace,Consolas,monospace;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}"
                    + ".stat.open .n{color:var(--amber)}.stat.fixed .n,.stat.pass .n{color:var(--pass)}"
                    + ".filters{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}"
                    + "input[type=search],select,textarea{font:inherit;color:var(--text);background:#272b31;"
                    + "border:1px solid var(--line2);border-radius:3px;padding:8px 10px}"
                    + "input[type=search]{flex:1;min-width:220px}"
                    + "input:focus,select:focus,textarea:focus{outline:1px solid var(--accent);border-color:var(--accent)}"
                    + "textarea{width:100%;font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:13px}"
                    + "button{font:600 13px 'Segoe UI',Inter,system-ui,sans-serif;color:var(--text);background:transparent;"
                    + "border:1px solid var(--line2);border-radius:3px;padding:8px 16px;cursor:pointer}"
                    + "button:hover{background:var(--hover);border-color:var(--dim)}"
                    + "button.primary{background:var(--accent);border-color:var(--accent);color:#0b1220}"
                    + "button.primary:hover{background:#8bbbf5}"
                    + "button.good{background:var(--pass);border-color:var(--pass);color:#07140e}"
                    + "button.good:hover{background:#6ad3a1}"
                    + ".reset{font-size:13px;color:var(--muted)}.btnrow{display:flex;gap:10px;margin-top:10px}"
                    + ".tablewrap{background:var(--panel);border:1px solid var(--line);border-radius:4px;overflow:hidden}"
                    + "table{width:100%;border-collapse:collapse}"
                    + "th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--line)}"
                    + "tbody tr:last-child td{border-bottom:0}"
                    + "th{font:700 11px ui-monospace,Consolas,monospace;letter-spacing:.08em;text-transform:uppercase;"
                    + "background:#2e333a;border-bottom-color:var(--line2)}"
                    + "th a{color:var(--muted)}th a:hover{color:var(--text);text-decoration:none}"
                    + "tbody tr:hover{background:var(--hover)}"
                    + ".empty{text-align:center;color:var(--muted);padding:28px}"
                    + ".details{white-space:pre-wrap;background:#272b31;border:1px solid var(--line);border-radius:3px;"
                    + "padding:12px;font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:13px}"
                    + ".resp{margin-bottom:14px}.resp .muted{margin-bottom:4px}"
                    + "img{max-width:100%;border:1px solid var(--line2);border-radius:3px;display:block}"
                    + "form.inline{margin:0}"
                    + "</style></head><body><div class=\"top\"><a class=\"brand\" href=\"/\">BUGSNAP</a>"
                    + "<span class=\"local\">local only - visible on this computer</span></div>"
                    + "<div class=\"wrap\">" + body + "</div></body></html>";
        }

        private static void sendHtml(HttpExchange ex, int code, String html) throws IOException {
            send(ex, code, "text/html; charset=utf-8", html);
        }

        private static void sendText(HttpExchange ex, int code, String text) throws IOException {
            send(ex, code, "text/plain; charset=utf-8", text);
        }

        private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'");
            ex.sendResponseHeaders(code, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }
    }

    // ==================================================================
    // Custom components
    // ==================================================================

    /** Flat panel with a 1px border and a very small corner radius. */
    private static class Card extends JPanel {
        Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(14, 16, 16, 16));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(PANEL);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            g2.setColor(BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
            g2.dispose();
        }
    }

    /** Outlined monospaced label such as FAIL or FIXED. */
    private static class Tag extends JLabel {
        private final Color colour;

        Tag(String text, Color colour) {
            super(text.toUpperCase(), SwingConstants.CENTER);
            this.colour = colour;
            setFont(MONO.deriveFont(Font.BOLD, 11f));
            setForeground(colour);
            setBorder(new EmptyBorder(3, 9, 3, 9));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 120));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small solid dot used in the status column and the Pass/Fail dropdown. */
    private static class DotIcon implements Icon {
        private final Color colour;

        DotIcon(Color colour) {
            this.colour = colour;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colour);
            g2.fillOval(x, y, 8, 8);
            g2.dispose();
        }

        public int getIconWidth() {
            return 8;
        }

        public int getIconHeight() {
            return 8;
        }
    }

    /** Top-bar navigation entry with an underline when selected. */
    private static class NavTab extends JComponent {
        private final String text;
        private boolean selected;
        private boolean hover;
        private Runnable action = () -> { };

        NavTab(String text) {
            this.text = text;
            int w = new JLabel().getFontMetrics(UI_BOLD).stringWidth(text) + 36;
            setPreferredSize(new Dimension(w, 48));
            setMaximumSize(new Dimension(w, 48));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    action.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void onClick(Runnable r) {
            this.action = r;
        }

        void setSelected(boolean s) {
            this.selected = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(UI_BOLD);
            g2.setColor(selected || hover ? TEXT : MUTED);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            if (selected) {
                g2.setColor(ACCENT);
                g2.fillRect(0, getHeight() - 2, getWidth(), 2);
            }
            g2.dispose();
        }
    }

    /** Flat self-painted button: filled accent, or outlined. */
    private static class FlatButton extends JButton {
        static final int PRIMARY = 0;
        static final int SECONDARY = 1;
        private final int kind;
        private boolean hover;

        FlatButton(String text, int kind) {
            super(text);
            this.kind = kind;
            setFont(UI_BOLD);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int w = Math.max(110, new JLabel().getFontMetrics(UI_BOLD).stringWidth(text) + 36);
            setPreferredSize(new Dimension(w, 34));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean active = getModel().isPressed() || hover;
            Color textColour;
            if (!isEnabled()) {
                g2.setColor(PANEL2);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                textColour = DIM;
            } else if (kind == PRIMARY) {
                g2.setColor(active ? ACCENT_HOVER : ACCENT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                textColour = ON_ACCENT;
            } else {
                if (active) {
                    g2.setColor(PANEL2);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                }
                g2.setColor(active ? DIM : BORDER2);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 5, 5);
                textColour = TEXT;
            }
            g2.setColor(textColour);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Dark, square dropdown with a small chevron. */
    private static class FlatComboUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(INPUT);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(MUTED);
                    g2.setStroke(new BasicStroke(1.6f));
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
                    g2.drawLine(cx, cy + 2, cx + 4, cy - 2);
                    g2.dispose();
                }
            };
            b.setBorder(BorderFactory.createEmptyBorder());
            b.setContentAreaFilled(false);
            b.setFocusPainted(false);
            b.setPreferredSize(new Dimension(26, 20));
            return b;
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            g.setColor(INPUT);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            javax.swing.plaf.basic.BasicComboPopup p = new javax.swing.plaf.basic.BasicComboPopup(comboBox);
            p.setBorder(BorderFactory.createLineBorder(BORDER2));
            p.getList().setBackground(INPUT);
            return p;
        }
    }

    /** Thin dark scrollbar with no arrow buttons. */
    private static class FlatScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = BORDER2;
            trackColor = INPUT;
        }

        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(INPUT);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) {
                return;
            }
            g.setColor(isThumbRollover() ? DIM : BORDER2);
            g.fillRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4);
        }
    }

    /** Draws the screenshot scaled to fit, centred, and resizes with the window. */
    private static class PreviewPanel extends JPanel {
        private BufferedImage image;

        PreviewPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(400, 260));
        }

        void setImage(BufferedImage img) {
            this.image = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setColor(INPUT);
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (image == null) {
                g2.setColor(BORDER2);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                        new float[]{5f, 4f}, 0f));
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

                g2.setFont(MONO.deriveFont(Font.BOLD, 12f));
                g2.setColor(MUTED);
                FontMetrics fm = g2.getFontMetrics();
                String a = "NO SCREENSHOT";
                int cy = getHeight() / 2;
                g2.drawString(a, (getWidth() - fm.stringWidth(a)) / 2, cy - 4);
                g2.setFont(MONO.deriveFont(11f));
                g2.setColor(DIM);
                fm = g2.getFontMetrics();
                String b = "BugSnap hides itself, then grabs the whole screen";
                g2.drawString(b, (getWidth() - fm.stringWidth(b)) / 2, cy + 16);
            } else {
                g2.setColor(BORDER);
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                int pad = 10;
                double scale = Math.min((double) (getWidth() - 2 * pad) / image.getWidth(),
                        (double) (getHeight() - 2 * pad) / image.getHeight());
                scale = Math.min(scale, 1.0);
                int w = Math.max(1, (int) (image.getWidth() * scale));
                int h = Math.max(1, (int) (image.getHeight() * scale));
                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;
                g2.drawImage(image, x, y, w, h, null);
                g2.setColor(BORDER2);
                g2.drawRect(x - 1, y - 1, w + 1, h + 1);
            }
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installTheme();
            new BugSnap().setVisible(true);
        });
    }
}