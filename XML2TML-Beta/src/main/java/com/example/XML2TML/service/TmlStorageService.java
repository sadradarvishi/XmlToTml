package com.example.XML2TML.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.XML2TML.security.TmlEntity;
import com.example.XML2TML.security.TmlRepository;
import com.example.XML2TML.service.XmlLayoutService.Layout;

@Service
public class TmlStorageService {
    private final TmlRepository repository;

    public TmlStorageService(TmlRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<TmlEntity> find(String transactionNumber) {
        return repository.findById(transactionNumber);
    }

    @Transactional(readOnly = true)
    public List<String> listTransactionNumbers() {
        return repository.findAllTransactionNumbers();
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> findExcel(String transactionNumber) {
        return repository.findExcelFile(transactionNumber);
    }

    public static String excelFileName(String transactionNumber) {
        return "TML-" + transactionNumber.replaceAll("[^A-Za-z0-9._-]", "_") + ".xlsx";
    }

    @Transactional
    public TmlEntity save(Layout layout, String sourceXml, byte[] workbook) {
        String transactionNumber = transactionNumber(layout);
        if (repository.existsById(transactionNumber)) {
            throw new IllegalArgumentException(
                    "Transaction number " + transactionNumber + " already exists.");
        }
        return repository.save(new TmlEntity(
                transactionNumber, nameSuffix(layout.groupName()), sourceXml, workbook));
    }

    public static String transactionNumber(Layout layout) {
        String id = layout.groupId() == null ? "" : layout.groupId().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("GROUP element has no ID attribute.");
        }
        return id;
    }

    static String nameSuffix(String groupName) {
        if (groupName == null) return "";
        int colon = groupName.indexOf(':');
        return colon < 0 ? groupName.trim() : groupName.substring(colon + 1).trim();
    }
}
