
package com.example.XML2TML.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.example.XML2TML.security.TmlEntity;
import com.example.XML2TML.service.ExcelExportService;
import com.example.XML2TML.service.XmlLayoutService;
import com.example.XML2TML.service.XmlLayoutService.FieldRow;
import com.example.XML2TML.service.XmlLayoutService.Layout;
import com.example.XML2TML.service.XmlLayoutService.SheetLayout;
import com.example.XML2TML.service.TmlStorageService;
import com.example.XML2TML.service.XmlFetchService;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.spring.security.AuthenticationContext;

import jakarta.annotation.security.PermitAll;

@Route("")
@PageTitle("XML to TML")
@PermitAll
public class MainView extends VerticalLayout implements BeforeEnterObserver {

    public static final String TRANSACTION_PARAM = "transaction";
    private final XmlLayoutService parser;
    private final ExcelExportService exporter;
    private final AuthenticationContext authenticationContext;
    private final TmlStorageService storage;
    private final XmlFetchService fetcher;

    private final VerticalLayout preview = new VerticalLayout();
    private final VerticalLayout downloads = new VerticalLayout();

    public MainView(
            XmlLayoutService parser,
            ExcelExportService exporter,
            AuthenticationContext authenticationContext,
            TmlStorageService storage,
            XmlFetchService fetcher
    ) {
        this.parser = parser;
        this.exporter = exporter;
        this.authenticationContext = authenticationContext;
        this.storage = storage;
        this.fetcher = fetcher;

        setWidthFull();
        setPadding(true);
        setSpacing(true);

        H1 title = new H1("XML to TML");
        Paragraph intro = new Paragraph(
                "Upload a GROUP XML definition to generate Excel worksheets "
                        + "for request layouts, response branches and collections.");

        Button logout = new Button("Sign out",
                event -> authenticationContext.logout());

        RouterLink history = new RouterLink("History", HistoryView.class);

        HorizontalLayout top = new HorizontalLayout(
                new VerticalLayout(title, intro), history, logout);
        top.setWidthFull();
        top.setAlignItems(Alignment.CENTER);
        top.setFlexGrow(1, top.getComponentAt(0));

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);

        upload.setAcceptedFileTypes(".xml", "text/xml", "application/xml");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(2 * 1024 * 1024);
        upload.setDropLabel(new Span("Drop XML file here or browse"));

        upload.addSucceededListener(event -> {
            try {
                processXml(buffer.getInputStream().readAllBytes());
            } catch (Exception ex) {
                showError(ex);
            } finally {
                upload.clearFileList();
            }
        });

        upload.addFileRejectedListener(event ->
                Notification.show(
                        "File rejected: " + event.getErrorMessage(),
                        5000,
                        Notification.Position.MIDDLE));

        TextField urlField = new TextField("");
        urlField.setPlaceholder("http://host.bki/xml/transactions/xxxxxx.xml");
        urlField.setClearButtonVisible(true);
        urlField.setWidth("40em");
        urlField.setErrorMessage("Invalid URL: it must end with six digits and .xml");

        Button loadUrl = new Button("Load", VaadinIcon.CLOUD_DOWNLOAD.create());
        loadUrl.addClickListener(event -> {
            String url = urlField.getValue().trim();
            if (!XmlFetchService.isValidUrl(url)) {
                urlField.setInvalid(true);
                Notification.show("Invalid URL", 5000, Notification.Position.MIDDLE);
                return;
            }
            urlField.setInvalid(false);
            try {
                processXml(fetcher.fetch(url));
            } catch (Exception ex) {
                showError(ex);
            }
        });
        loadUrl.addClickShortcut(Key.ENTER).listenOn(urlField);
        urlField.addValueChangeListener(event -> urlField.setInvalid(false));

        HorizontalLayout urlRow = new HorizontalLayout(urlField, loadUrl);
        urlRow.setAlignItems(Alignment.BASELINE);

        add(
                top,
                new H2("1.1 Upload XML"),
                upload,
                new H2("1.2 Place XML Url"),
                urlRow,
                downloads,
                new H2("2. Layout preview"),
                preview
        );
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        event.getLocation().getQueryParameters()
                .getSingleParameter(TRANSACTION_PARAM)
                .ifPresent(this::showSaved);
    }

    private void showSaved(String txn) {
        Optional<TmlEntity> saved = storage.find(txn);
        if (saved.isEmpty()) {
            preview.removeAll();
            downloads.removeAll();
            Notification.show("Transaction " + txn + " was not found.",
                    5000, Notification.Position.MIDDLE);
            return;
        }
        try {
            Layout layout = parser.parse(new ByteArrayInputStream(
                    saved.get().getSourceXml().getBytes(StandardCharsets.UTF_8)));
            showPreview(layout);
            showDownload(layout, saved.get().getExcelFile());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void processXml(byte[] xmlBytes) throws Exception {
        Layout layout = parser.parse(new ByteArrayInputStream(xmlBytes));

        String txn = TmlStorageService.transactionNumber(layout);
        Optional<TmlEntity> saved = storage.find(txn);

        byte[] workbook;
        String message;
        if (saved.isPresent()) {
            layout = parser.parse(new ByteArrayInputStream(
                    saved.get().getSourceXml().getBytes(StandardCharsets.UTF_8)));
            workbook = saved.get().getExcelFile();
            message = "Transaction " + txn
                    + " already exists. Loaded the saved Excel workbook.";
        } else {
            workbook = exporter.createWorkbook(layout);
            storage.save(layout,
                    new String(xmlBytes, StandardCharsets.UTF_8), workbook);
            message = "Saved transaction " + txn + ". Generated "
                    + layout.sheets().size() + " layout worksheets.";
        }

        showPreview(layout);
        showDownload(layout, workbook);

        Notification.show(message);
    }

    private void showError(Exception ex) {
        preview.removeAll();
        downloads.removeAll();

        Notification.show(
                "Could not process XML: " + ex.getMessage(),
                7000,
                Notification.Position.MIDDLE);
    }

    private void showPreview(Layout layout) {
        preview.removeAll();

        preview.add(new H3(
                layout.groupId() + " — " + layout.groupName()));

        for (SheetLayout sheet : layout.sheets()) {
            preview.add(new H4(
                    sheet.name() + " (" + sheet.rows().size() + " rows)"));

            if (!sheet.condition().isBlank()) {
                preview.add(new Paragraph(sheet.condition()));
            }

            Grid<FieldRow> grid = new Grid<>();
            grid.addColumn(FieldRow::from).setHeader("From");
            grid.addColumn(FieldRow::to).setHeader("To");
            grid.addColumn(FieldRow::length).setHeader("Length");
            grid.addColumn(FieldRow::dataType).setHeader("Data Type");
            grid.addColumn(FieldRow::description).setHeader("Description");
            grid.addColumn(FieldRow::format).setHeader("Format");
            grid.addColumn(FieldRow::repeats).setHeader("Repeats");
            grid.addColumn(FieldRow::recordLength)
                    .setHeader("Record Length");

            grid.setItems(sheet.rows());
            grid.setWidthFull();
            grid.setAllRowsVisible(true);

            preview.add(grid);
        }
    }

    private void showDownload(Layout layout, byte[] workbook) {
        downloads.removeAll();

        String id = layout.groupId().replaceAll(
                "[^A-Za-z0-9._-]", "_");

        StreamResource resource = new StreamResource(
                "TML-" + id + ".xlsx",
                () -> new ByteArrayInputStream(workbook));

        Button button = new Button("Download Excel workbook",
                VaadinIcon.DOWNLOAD.create());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Anchor download = new Anchor(resource, "");
        download.getElement().setAttribute("download", true);
        download.add(button);

        downloads.add(download);
    }
}
