package com.agentos.providervault;

import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providerreceipts.ProviderReceiptService;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Explicit writer-owned services; never registers or replaces the application's reader datasource.
 */
record ProviderCommandRuntime(
    ProviderReceiptService receipts,
    ProviderCompletionBoundary completion,
    ProviderWriteAdmission admission) {
  static ProviderCommandRuntime assemble(DataSource datasource) {
    if (datasource == null) throw new IllegalArgumentException("INVALID_CONFIGURATION");
    var jdbc = new JdbcTemplate(datasource);
    var manager = new DataSourceTransactionManager(datasource);
    var attributes = new AnnotationTransactionAttributeSource();
    var connections = new ProxyFactory(new ProviderConnectionService(jdbc));
    connections.setProxyTargetClass(true);
    connections.addAdvice(new TransactionInterceptor(manager, attributes));
    var receipts =
        new ProxyFactory(
            new ProviderReceiptService(jdbc, (ProviderConnectionService) connections.getProxy()));
    receipts.setProxyTargetClass(true);
    receipts.addAdvice(new TransactionInterceptor(manager, attributes));
    var service = (ProviderReceiptService) receipts.getProxy();
    return new ProviderCommandRuntime(
        service,
        new ProviderCompletionBoundary(manager, jdbc, service),
        new ProviderWriteAdmission(datasource));
  }
}
