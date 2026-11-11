package com.example.XML2TML.ui;

import java.io.ByteArrayInputStream;
import java.util.List;

import com.example.XML2TML.service.TmlStorageService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;

import jakarta.annotation.security.PermitAll;

@Route("history")
@PageTitle("History | XML to TML")
@PermitAll
public class HistoryView extends VerticalLayout {

    private final TmlStorageService storage;

    public HistoryView(TmlStorageService storage) {
        this.storage = storage;

        setWidthFull();
        setPadding(true);
        setSpacing(true);

        List<String> transactions = storage.listTransactionNumbers();

        Grid<String> grid = new Grid<>();
        grid.addColumn(txn -> txn)
                .setHeader("Transaction Number")
                .setAutoWidth(true)
                .setSortable(true);
        grid.addColumn(TmlStorageService::excelFileName)
                .setHeader("Excel File")
                .setAutoWidth(true);
        grid.addComponentColumn(HistoryView::visitLink)
                .setHeader("Visit")
                .setAutoWidth(true);
        grid.addComponentColumn(this::downloadButton)
                .setHeader("Download")
                .setAutoWidth(true);

        grid.setItems(transactions);
        grid.setWidthFull();
        grid.setAllRowsVisible(true);

        add(
                new H1("History"),
                new RouterLink("← Back to XML to TML", MainView.class),
                transactions.isEmpty()
                        ? new Paragraph("No transactions saved yet.")
                        : grid
        );
    }

    private static RouterLink visitLink(String txn) {
        Button button = new Button("Visit", VaadinIcon.EYE.create());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        RouterLink link = new RouterLink("", MainView.class);
        link.setQueryParameters(QueryParameters.of(MainView.TRANSACTION_PARAM, txn));
        link.add(button);
        return link;
    }

    private Anchor downloadButton(String txn) {
        StreamResource resource = new StreamResource(
                TmlStorageService.excelFileName(txn),
                () -> new ByteArrayInputStream(
                        storage.findExcel(txn).orElse(new byte[0])));

        Button button = new Button("Download", VaadinIcon.DOWNLOAD.create());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        Anchor anchor = new Anchor(resource, "");
        anchor.getElement().setAttribute("download", true);
        anchor.add(button);
        return anchor;
    }
}
