package com.example.XML2TML.security;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TmlRepository extends JpaRepository<TmlEntity, String> {

    @Query("select t.transactionNumber from TmlEntity t order by t.transactionNumber")
    List<String> findAllTransactionNumbers();

    @Query("select t.excelFile from TmlEntity t where t.transactionNumber = :txn")
    Optional<byte[]> findExcelFile(@Param("txn") String transactionNumber);
}
