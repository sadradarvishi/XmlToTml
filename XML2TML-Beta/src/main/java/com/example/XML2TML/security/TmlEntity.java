package com.example.XML2TML.security;

import jakarta.persistence.*;

import java.util.Date;

@Entity
@Table(name = "tml_entity")
public class TmlEntity {
    @Id
    @Column(name = "transaction_number", nullable = false)
    private String transactionNumber;

    @Column(name = "group_attributes", columnDefinition = "TEXT")
    private String groupAttributes;

    @Column(name = "source_xml", nullable = false, columnDefinition = "TEXT")
    private String sourceXml;

    @Column(name = "excel_file", nullable = false, columnDefinition = "BLOB")
    private byte[] excelFile;

    @Column(name = "created_at", nullable = false)
    private Date createdAt;

    protected TmlEntity() {}

    public TmlEntity(String transactionNumber, String groupAttributes,
                     String sourceXml, byte[] excelFile) {
        this.transactionNumber = transactionNumber;
        this.groupAttributes = groupAttributes;
        this.sourceXml = sourceXml;
        this.excelFile = excelFile;
    }

    @PrePersist
    void onCreate() { this.createdAt = new Date(); }

    public String getTransactionNumber() {return transactionNumber;}
    public String getGroupAttributes() {return groupAttributes;}
    public String getSourceXml() {return sourceXml;}
    public byte[] getExcelFile() {return excelFile;}
    public Date getCreatedAt() {return createdAt;}
}

