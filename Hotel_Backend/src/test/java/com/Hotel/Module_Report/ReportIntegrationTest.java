package com.Hotel.Module_Report;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql("/report-fixture.sql")
class ReportIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ROOT = "/api/reports/revenue";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private org.springframework.test.web.servlet.request.RequestPostProcessor login(String user,String password) {
        return request -> {
            request.removeHeader("Authorization");
            return httpBasic(user,password).postProcessRequest(request);
        };
    }
    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).with(login("admin@test.local","test-password"));
    }
    private MockHttpServletRequestBuilder request(String path) {return request(path,"2026-10-01","2026-10-03");}
    private MockHttpServletRequestBuilder request(String path,String from,String to) {
        return get("/api/reports/"+path).param("from",from).param("to",to);
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeAll
    static void schema(@Autowired DataSource datasource) throws Exception {
        // Execute the actual delivered schema, not a reduced imitation of production tables.
        String sql = Files.readString(Path.of("../Hotel_Database/sql/01_schema.sql"));
        try (var connection = datasource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @AfterAll
    static void stop() throws IOException { POSTGRES.close(); }

    @Test
    void summaryUsesClosedInvoicesAndLocalDayBoundaries() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedInvoiceCount").value(2))
                .andExpect(jsonPath("$.roomAmount").value(150.15))
                .andExpect(jsonPath("$.serviceAmount").value(20.20))
                .andExpect(jsonPath("$.extrasAmount").value(3.30))
                .andExpect(jsonPath("$.discountAmount").value(4.40))
                .andExpect(jsonPath("$.taxAmount").value(5.50))
                .andExpect(jsonPath("$.invoiceTotal").value(174.75));
    }

    @Test
    void summaryFiltersBranch() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-01").param("to", "2026-10-03")
                        .param("branchId", "91001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedInvoiceCount").value(1))
                .andExpect(jsonPath("$.invoiceTotal").value(124.70));
    }

    @Test
    void emptySummaryReturnsZero() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "2026-01-01").param("to", "2026-01-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedInvoiceCount").value(0))
                .andExpect(jsonPath("$.invoiceTotal").value(0));
    }

    @Test
    void dailySeriesFillsGapsAndUsesLocalDate() throws Exception {
        mvc.perform(get(ROOT + "/by-period").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupBy").value("DAY"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].periodStart").value("2026-10-01"))
                .andExpect(jsonPath("$.items[0].invoiceTotal").value(124.70))
                .andExpect(jsonPath("$.items[1].invoiceTotal").value(0))
                .andExpect(jsonPath("$.items[2].invoiceTotal").value(50.05));
    }

    @Test
    void monthlySeriesIncludesEmptyMonthAndRespectsPartialMonth() throws Exception {
        mvc.perform(get(ROOT + "/by-period").param("from", "2026-10-02").param("to", "2026-11-05")
                        .param("groupBy", "MONTH").param("branchId", "91002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].periodStart").value("2026-10-01"))
                .andExpect(jsonPath("$.items[0].invoiceTotal").value(50.05))
                .andExpect(jsonPath("$.items[1].invoiceTotal").value(0));
    }

    @Test
    void branchSeriesIncludesInactiveAndEmptyBranches() throws Exception {
        mvc.perform(get(ROOT + "/by-branch").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].invoiceTotal").value(124.70))
                .andExpect(jsonPath("$.items[1].invoiceTotal").value(50.05))
                .andExpect(jsonPath("$.items[2].invoiceTotal").value(0));
    }

    @Test
    void branchSeriesCanFilterOneBranch() throws Exception {
        mvc.perform(get(ROOT + "/by-branch").param("from", "2026-10-01").param("to", "2026-10-03")
                        .param("branchId", "91002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].branchId").value(91002));
    }

    @Test
    void rejectsInvalidFilters() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-04").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-01").param("to", "2026-10-03")
                        .param("branchId", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedOrMissingParameters() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "bad").param("to", "2026-10-03"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/by-period").param("from", "2026-10-01").param("to", "2026-10-03")
                        .param("groupBy", "YEAR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBranchReturns404() throws Exception {
        mvc.perform(get(ROOT + "/summary").param("from", "2026-10-01").param("to", "2026-10-03")
                        .param("branchId", "99999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test void revenueSubtractsOnlyDoneRefundsInCompletionPeriod() throws Exception {
        refunds();
        mvc.perform(request("revenue/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundCount").value(3))
                .andExpect(jsonPath("$.refundTotal").value(70))
                .andExpect(jsonPath("$.netRevenue").value(104.75));
        mvc.perform(request("revenue/by-period"))
                .andExpect(jsonPath("$.summary.netRevenue").value(104.75))
                .andExpect(jsonPath("$.items[1].refundTotal").value(70));
        mvc.perform(request("revenue/by-branch"))
                .andExpect(jsonPath("$.summary.netRevenue").value(104.75))
                .andExpect(jsonPath("$.items[0].netRevenue").value(54.70));
    }
    private void refunds() {
        jdbc.update("""
            INSERT INTO hotel.payments(id,reservation_id,amount,method,status,obligation_key)
            OVERRIDING SYSTEM VALUE VALUES (91001,91001,100,'CASH','PAID','RP1'),(91002,91005,100,'CASH','PAID','RP2')
            """);
        jdbc.update("""
            INSERT INTO hotel.refunds(payment_id,reservation_id,amount,status,idempotency_key,completed_at) VALUES
            (91001,91001,10,'DONE','RF1','2026-10-02 00:00:00+07'),
            (91001,91001,20,'DONE','RF2','2026-10-02 00:00:00+07'),
            (91002,91005,40,'DONE','RF3','2026-10-02 00:00:00+07'),
            (91001,91001,100,'PENDING','RF4',NULL),
            (91001,91001,5,'DONE','RF5','2026-09-01 00:00:00+07')
            """);
    }
    @Test void refundOnlyPeriodHasDataAndCanBeNegative() throws Exception {
        refunds();
        mvc.perform(request("revenue/summary","2026-10-02","2026-10-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasData").value(true))
                .andExpect(jsonPath("$.closedInvoiceCount").value(0))
                .andExpect(jsonPath("$.netRevenue").value(-70));
    }
    @Test void roomTypeFilterAllocatesMoneyWithoutCountingWholeInvoice() throws Exception {
        refunds();
        mvc.perform(request("revenue/summary").param("roomTypeIds","91001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceTotal").value(74.82))
                .andExpect(jsonPath("$.refundTotal").value(18))
                .andExpect(jsonPath("$.netRevenue").value(56.82));
    }
    @Test void weeklyPeriodsUseMondayAndFillGaps() throws Exception {
        mvc.perform(request("revenue/by-period","2026-10-01","2026-10-12").param("groupBy","WEEK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].periodStart").value("2026-09-28"))
                .andExpect(jsonPath("$.items[0].invoiceTotal").value(974.75))
                .andExpect(jsonPath("$.items[1].invoiceTotal").value(0));
    }
    @Test void multipleBranchesAndInvalidCombinations() throws Exception {
        mvc.perform(request("revenue/summary").param("branchIds","91001,91002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceTotal").value(174.75));
        mvc.perform(request("revenue/summary").param("branchId","91001").param("branchIds","91002"))
                .andExpect(status().isBadRequest());
        mvc.perform(request("revenue/summary").param("branchId","91002").param("roomTypeIds","91001"))
                .andExpect(status().isBadRequest());
    }
    @Test void rejectsPeriodsBeyondOneCalendarYear() throws Exception {
        mvc.perform(request("revenue/summary","2025-10-01","2026-10-02"))
                .andExpect(status().isBadRequest());
        mvc.perform(request("revenue/summary","2025-10-01","2026-10-01"))
                .andExpect(status().isOk());
    }
    @Test void occupancyUsesRoomNightsExcludesMaintenanceAndCancelledNoShow() throws Exception {
        mvc.perform(request("occupancy/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.soldRoomNights").value(5))
                .andExpect(jsonPath("$.availableRoomNights").value(11))
                .andExpect(jsonPath("$.occupancyRate").value(45.45));
        mvc.perform(request("occupancy/by-period"))
                .andExpect(jsonPath("$.items[1].availableRoomNights").value(3))
                .andExpect(jsonPath("$.items[1].soldRoomNights").value(2));
        mvc.perform(request("occupancy/by-branch").param("roomTypeIds","91001"))
                .andExpect(jsonPath("$.summary.availableRoomNights").value(5))
                .andExpect(jsonPath("$.summary.soldRoomNights").value(2));
    }
    @Test void zeroCapacityProducesNullRateAndEmptyState() throws Exception {
        mvc.perform(request("occupancy/summary").param("branchId","91003"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasData").value(false))
                .andExpect(jsonPath("$.occupancyRate").isEmpty());
    }
    @Test void reservationsCountCurrentStatusesByCreationDate() throws Exception {
        mvc.perform(request("reservations/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalReservations").value(7))
                .andExpect(jsonPath("$.cancelledCount").value(1)).andExpect(jsonPath("$.noShowCount").value(1))
                .andExpect(jsonPath("$.cancellationRate").value(14.29));
        mvc.perform(request("reservations/by-period").param("roomTypeIds","91001"))
                .andExpect(jsonPath("$.summary.totalReservations").value(3));
    }
    @Test void promotionsSeparateReversedUsageAndAvoidDuplicateRevenue() throws Exception {
        refunds();
        mvc.perform(request("promotions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].usageCount").value(1))
                .andExpect(jsonPath("$.items[0].reversedUsageCount").value(1))
                .andExpect(jsonPath("$.items[0].discountTotal").value(10))
                .andExpect(jsonPath("$.items[0].invoiceTotal").value(174.75))
                .andExpect(jsonPath("$.items[0].refundTotal").value(30));
    }
    @Test void enforcesAuthenticationRoleAndBranchScope() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/reports/options"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("revenue/summary").with(login("guest@test.local","test-password")))
                .andExpect(status().isForbidden());
        mvc.perform(request("revenue/summary").with(login("disabled@test.local","test-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("revenue/summary").with(login("admin@test.local","wrong-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("revenue/summary").with(login("manager@test.local","test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.branchIds[0]").value(91001))
                .andExpect(jsonPath("$.invoiceTotal").value(124.70));
        mvc.perform(request("revenue/summary").with(login("manager@test.local","test-password")).param("branchIds","91001,91002"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/options").with(login("manager@test.local","test-password")))
                .andExpect(jsonPath("$.branches.length()").value(1))
                .andExpect(jsonPath("$.roomTypes.length()").value(2));
    }
    @Test void excelIsValidNumericAndRespectsScope() throws Exception {
        var response=mvc.perform(request("export").param("reportType","REVENUE").param("view","summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            org.junit.jupiter.api.Assertions.assertNotNull(workbook.getSheet("Report info"));
            var sheet=workbook.getSheet("Data");
            boolean found=false;
            for(var header:sheet.getRow(0)) if(header.getStringCellValue().equals("netRevenue")) {
                org.junit.jupiter.api.Assertions.assertEquals(174.75,sheet.getRow(1).getCell(header.getColumnIndex()).getNumericCellValue(),0.001);
                found=true;
            }
            org.junit.jupiter.api.Assertions.assertTrue(found);
        }
        mvc.perform(request("export").param("reportType","REVENUE").param("branchIds","91002")
                .with(login("manager@test.local","test-password")))
                .andExpect(status().isForbidden());
    }

    @Test void allReportTypesExportFilteredNumbersAndRealDateCells() throws Exception {
        for(String type:new String[]{"OCCUPANCY","RESERVATIONS","PROMOTIONS"}) {
            var response=mvc.perform(request("export").param("reportType",type).param("view","by-period")
                    .param("branchIds","91001").param("groupBy","WEEK"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                var cell=workbook.getSheet("Data").getRow(1).getCell(0);
                org.junit.jupiter.api.Assertions.assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell));
                org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.of(2026,9,28),cell.getLocalDateTimeCellValue().toLocalDate());
                org.junit.jupiter.api.Assertions.assertEquals("[91001]",workbook.getSheet("Report info").getRow(4).getCell(1).getStringCellValue());
            }
        }
    }
    @Test void zeroPriceSelectedRoomTypeStillCountsMatchingReservations() throws Exception {
        jdbc.update("UPDATE hotel.reservation_details SET subtotal=0 WHERE id=91002");
        mvc.perform(request("reservations/summary").param("roomTypeIds","91002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalReservations").value(1));
        mvc.perform(request("revenue/summary").param("roomTypeIds","91002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceTotal").value(0))
                .andExpect(jsonPath("$.closedInvoiceCount").value(1));
    }
    @Test void weeklySeriesCrossesYearAndRetainsMondayAnchor() throws Exception {
        mvc.perform(request("revenue/by-period","2026-12-31","2027-01-05").param("groupBy","WEEK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].periodStart").value("2026-12-28"))
                .andExpect(jsonPath("$.items[1].periodStart").value("2027-01-04"))
                .andExpect(jsonPath("$.hasData").value(false));
    }
}
