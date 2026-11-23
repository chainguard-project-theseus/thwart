-- Records the integration-test seed lacks, with fixed ids, so pages.txt can open their pages.
-- Records created through the forms get random ids, so their detail pages are opened here instead.

INSERT INTO vendors (id, code, name, contact_person, email, phone, address, npwp, payment_term_days, active, created_at, updated_at)
VALUES ('be000000-0000-0000-0000-000000000001', 'VND-001', 'PT Cloud Nusantara', 'Rina', 'billing@cloud.example', '021-5550001', 'Jakarta', '01.234.567.8-901.000', 30, true, NOW(), NOW());

INSERT INTO bills (id, bill_number, id_vendor, vendor_invoice_number, bill_date, due_date, amount, tax_amount, status, notes, created_at, updated_at)
VALUES ('be070000-0000-0000-0000-000000000001', 'BILL-2025-0001', 'be000000-0000-0000-0000-000000000001', 'CN-5501', '2025-06-01', '2025-07-01', 5550000, 550000, 'DRAFT', 'Server bulan Juni', NOW(), NOW());
INSERT INTO bill_lines (id, id_bill, id_expense_account, description, quantity, unit_price, tax_rate, tax_amount, amount, line_order, created_at, updated_at)
VALUES ('be070000-0000-0000-0000-000000000101', 'be070000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000102', 'Cloud server Juni 2025', 1, 5000000, 11, 550000, 5000000, 1, NOW(), NOW());

INSERT INTO fixed_assets (id, asset_code, name, description, id_category, purchase_date, purchase_cost, supplier, invoice_number,
  depreciation_method, useful_life_months, residual_value, depreciation_start_date, accumulated_depreciation, book_value,
  depreciation_periods_completed, status, id_asset_account, id_accumulated_depreciation_account, id_depreciation_expense_account,
  auto_post, location, serial_number, brand, model, custodian, asset_condition, created_at, updated_at)
VALUES
('be010000-0000-0000-0000-000000000001', 'AST-001', 'Laptop Developer', 'Laptop untuk tim development', 'a0000000-0000-0000-0000-000000000001',
  '2025-01-10', 24000000, 'PT Komputer Jaya', 'KJ-2025-011', 'STRAIGHT_LINE', 48, 0, '2025-02-01', 0, 24000000,
  0, 'ACTIVE', '10000000-0000-0000-0000-000000000121', '10000000-0000-0000-0000-000000000122', '50000000-0000-0000-0000-000000000107',
  false, 'Kantor Depok', 'SN-LTP-0001', 'Lenovo', 'ThinkPad T14', 'Budi Santoso', 'GOOD', NOW(), NOW()),
('be010000-0000-0000-0000-000000000002', 'AST-002', 'Printer Lama', 'Printer kantor', 'a0000000-0000-0000-0000-000000000001',
  '2025-01-15', 12000000, 'PT Komputer Jaya', 'KJ-2025-015', 'STRAIGHT_LINE', 48, 0, '2025-02-01', 0, 12000000,
  0, 'DISPOSED', '10000000-0000-0000-0000-000000000121', '10000000-0000-0000-0000-000000000122', '50000000-0000-0000-0000-000000000107',
  false, 'Kantor Depok', 'SN-PRN-0002', 'Epson', 'L3210', 'Dewi Lestari', 'MAJOR_DAMAGE', NOW(), NOW());
UPDATE fixed_assets SET disposal_date = '2025-05-20', disposal_type = 'WRITTEN_OFF', disposal_proceeds = 0,
  gain_loss_on_disposal = -12000000, disposal_notes = 'Rusak berat' WHERE id = 'be010000-0000-0000-0000-000000000002';
INSERT INTO asset_disposals (id, record_number, id_fixed_asset, disposal_type, disposal_date, reason, proceeds, purchase_cost,
  accumulated_depreciation, book_value, gain_loss, status, created_by, created_at, updated_at)
VALUES ('be020000-0000-0000-0000-000000000001', 'DSP-2025-0001', 'be010000-0000-0000-0000-000000000002', 'WRITTEN_OFF', '2025-05-20',
  'Rusak berat, tidak ekonomis diperbaiki', 0, 12000000, 0, 12000000, -12000000, 'POSTED', 'admin', NOW(), NOW());
INSERT INTO depreciation_entries (id, id_fixed_asset, period_number, period_start, period_end, depreciation_amount, accumulated_depreciation, book_value, status, generated_at, created_at, updated_at)
VALUES
('be010000-0000-0000-0000-000000000101', 'be010000-0000-0000-0000-000000000001', 1, '2025-02-01', '2025-02-28', 500000, 500000, 23500000, 'PENDING', NOW(), NOW(), NOW()),
('be010000-0000-0000-0000-000000000102', 'be010000-0000-0000-0000-000000000001', 2, '2025-03-01', '2025-03-31', 500000, 1000000, 23000000, 'PENDING', NOW(), NOW(), NOW());

INSERT INTO amortization_schedules (id, code, name, description, schedule_type, id_source_account, id_target_account, total_amount,
  period_amount, start_date, end_date, frequency, total_periods, completed_periods, amortized_amount, remaining_amount, auto_post,
  post_day, status, created_at, updated_at, created_by)
VALUES ('be030000-0000-0000-0000-000000000001', 'AMR-001', 'Sewa Kantor 2025', 'Sewa kantor dibayar di muka setahun', 'PREPAID_EXPENSE',
  '10000000-0000-0000-0000-000000000106', '50000000-0000-0000-0000-000000000106', 12000000, 1000000, '2025-01-01', '2025-12-31',
  'MONTHLY', 12, 0, 0, 12000000, false, 1, 'ACTIVE', NOW(), NOW(), 'admin');
INSERT INTO amortization_entries (id, id_schedule, period_number, period_start, period_end, amount, status, generated_at, created_at, updated_at)
SELECT ('be030000-0000-0000-0000-0000000001' || lpad(m::text, 2, '0'))::uuid, 'be030000-0000-0000-0000-000000000001', m,
  make_date(2025, m, 1), (make_date(2025, m, 1) + interval '1 month - 1 day')::date, 1000000, 'PENDING', NOW(), NOW(), NOW()
FROM generate_series(1, 12) m;

INSERT INTO analysis_reports (id, title, report_type, industry, period_start, period_end, ai_source, ai_model, executive_summary,
  metrics, findings, recommendations, risks, created_at, updated_at, created_by)
VALUES ('be040000-0000-0000-0000-000000000001', 'Analisis Keuangan Semester I 2025', 'FINANCIAL_HEALTH', 'it_service', '2025-01-01', '2025-06-30',
  'bench', 'none', 'Pendapatan stabil, beban server naik.',
  '[{"name":"Margin Bersih","value":"18%","previousValue":"15%","change":"+3%","status":"positive"}]',
  '[{"category":"Beban","severity":"medium","description":"Beban server naik 20%."}]',
  '[{"priority":"high","description":"Negosiasi ulang kontrak cloud.","impact":"Hemat 10%"}]',
  '[{"severity":"low","description":"Piutang terkonsentrasi pada satu klien.","mitigation":"Tambah klien baru."}]',
  NOW(), NOW(), 'admin');

INSERT INTO bank_statements (id, id_bank_account, id_parser_config, statement_period_start, statement_period_end, opening_balance,
  closing_balance, original_filename, total_items, total_debit, total_credit, imported_at, imported_by, created_at, updated_at, created_by)
VALUES ('be050000-0000-0000-0000-000000000001', 'b0600000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
  '2025-05-01', '2025-05-31', 100000000, 98000000, 'bca-2025-05.csv', 3, 7000000, 5000000, NOW(), 'admin', NOW(), NOW(), 'admin');
INSERT INTO bank_statement_items (id, id_bank_statement, line_number, transaction_date, description, debit_amount, credit_amount, balance, raw_line, match_status)
VALUES
('be050000-0000-0000-0000-000000000101', 'be050000-0000-0000-0000-000000000001', 1, '2025-05-05', 'TRSF E-BANKING CR PT ABC', 0, 5000000, 105000000, '05/05/2025,TRSF E-BANKING CR PT ABC,0,5000000', 'UNMATCHED'),
('be050000-0000-0000-0000-000000000102', 'be050000-0000-0000-0000-000000000001', 2, '2025-05-10', 'BIAYA ADM', 15000, 0, 104985000, '10/05/2025,BIAYA ADM,15000,0', 'UNMATCHED'),
('be050000-0000-0000-0000-000000000103', 'be050000-0000-0000-0000-000000000001', 3, '2025-05-20', 'TRSF E-BANKING DB CLOUD', 6985000, 0, 98000000, '20/05/2025,TRSF E-BANKING DB CLOUD,6985000,0', 'UNMATCHED');
INSERT INTO bank_reconciliations (id, id_bank_account, id_bank_statement, period_start, period_end, status, book_balance, bank_balance,
  total_statement_items, matched_count, unmatched_bank_count, unmatched_book_count, notes, created_at, updated_at, created_by)
VALUES ('be060000-0000-0000-0000-000000000001', 'b0600000-0000-0000-0000-000000000001', 'be050000-0000-0000-0000-000000000001',
  '2025-05-01', '2025-05-31', 'IN_PROGRESS', 97500000, 98000000, 3, 0, 3, 0, 'Rekonsiliasi Mei', NOW(), NOW(), 'admin');

INSERT INTO contracts (id, contract_number, title, id_client, start_date, end_date, contract_value, notes, created_by, created_at, updated_at)
VALUES ('be080000-0000-0000-0000-000000000001', 'KTR-2025-001', 'Pemeliharaan Website 2025', 'c0500000-0000-0000-0000-000000000001',
  '2025-01-01', '2025-12-31', 120000000, 'Termin bulanan', 'admin', NOW(), NOW());

INSERT INTO fiscal_periods (id, year, month, status, notes, created_at, updated_at)
SELECT ('be090000-0000-0000-0000-0000000000' || lpad(m::text, 2, '0'))::uuid, 2025, m, CASE WHEN m = 1 THEN 'MONTH_CLOSED' ELSE 'OPEN' END,
  NULL, NOW(), NOW()
FROM generate_series(1, 6) m;
UPDATE fiscal_periods SET month_closed_at = NOW(), month_closed_by = 'admin' WHERE id = 'be090000-0000-0000-0000-000000000001';

INSERT INTO bill_of_materials (id, id_product, code, name, description, output_quantity, active, created_at, updated_at)
VALUES ('be0a0000-0000-0000-0000-000000000001', 'd0911002-0000-0000-0000-000000000003', 'BOM-001', 'Resep Kue Bolu', 'Satu loyang', 1, true, NOW(), NOW());
INSERT INTO bill_of_material_lines (id, id_bill_of_material, id_component, quantity, notes, line_order, created_at, updated_at)
VALUES
('be0a0000-0000-0000-0000-000000000101', 'be0a0000-0000-0000-0000-000000000001', 'd0911002-0000-0000-0000-000000000001', 0.5, 'Ayak dulu', 1, NOW(), NOW()),
('be0a0000-0000-0000-0000-000000000102', 'be0a0000-0000-0000-0000-000000000001', 'd0911002-0000-0000-0000-000000000002', 0.3, NULL, 2, NOW(), NOW());
INSERT INTO production_orders (id, order_number, id_bill_of_material, quantity, order_date, planned_completion_date, status, notes, created_at, updated_at, created_by)
VALUES ('be0b0000-0000-0000-0000-000000000001', 'PO-2025-0001', 'be0a0000-0000-0000-0000-000000000001', 10, '2025-06-10', '2025-06-12', 'DRAFT', 'Pesanan arisan', NOW(), NOW(), 'admin');

INSERT INTO recurring_transactions (id, name, id_journal_template, amount, description, frequency, day_of_month, start_date,
  next_run_date, last_run_date, status, skip_weekends, auto_post, total_runs, created_at, updated_at, created_by)
VALUES ('be0c0000-0000-0000-0000-000000000001', 'Langganan Cloud Bulanan', 'e0000000-0000-0000-0000-000000000005', 2500000,
  'Tagihan cloud bulanan', 'MONTHLY', 5, '2025-01-05', '2025-07-05', '2025-06-05', 'ACTIVE', false, false, 6, NOW(), NOW(), 'admin');

INSERT INTO documents (id, filename, original_filename, content_type, file_size, storage_path, checksum_sha256, uploaded_by, uploaded_at, created_at, updated_at)
VALUES
('be0d0000-0000-0000-0000-000000000001', 'sp2dk-2025.pdf', 'SP2DK S-123.pdf', 'application/pdf', 1024, 'bench/sp2dk-2025.pdf',
  '0000000000000000000000000000000000000000000000000000000000000001', 'admin', NOW(), NOW(), NOW()),
('be0d0000-0000-0000-0000-000000000002', 'rekening-2025-05.pdf', 'Rekening Koran Mei 2025.pdf', 'application/pdf', 2048, 'bench/rekening-2025-05.pdf',
  '0000000000000000000000000000000000000000000000000000000000000002', 'admin', NOW(), NOW(), NOW());

INSERT INTO tax_inquiry_cases (id, inquiry_type, tax_year, letter_number, letter_date, received_date, tax_office, subject, deadline,
  status, id_letter_document, created_by, created_at, updated_at)
VALUES ('be0e0000-0000-0000-0000-000000000001', 'SP2DK', 2024, 'S-123/WPJ.04/2025', '2025-06-02', '2025-06-05', 'KPP Pratama Depok',
  'Permintaan penjelasan PPN 2024', '2025-07-05', 'OPEN', 'be0d0000-0000-0000-0000-000000000001', 'admin', NOW(), NOW());
INSERT INTO tax_inquiry_items (id, id_case, item_no, description, created_by, created_at)
VALUES ('be0e0000-0000-0000-0000-000000000101', 'be0e0000-0000-0000-0000-000000000001', 1, 'Selisih PPN keluaran Maret 2024', 'admin', NOW());

INSERT INTO bank_statement_archives (id, id_bank_account, period_month, id_document, retention_until, status, uploaded_by, created_at, updated_at)
VALUES ('be100000-0000-0000-0000-000000000001', 'b0600000-0000-0000-0000-000000000001', '2025-05-01', 'be0d0000-0000-0000-0000-000000000002',
  '2035-05-01', 'ACTIVE', 'admin', NOW(), NOW());

INSERT INTO withholding_payments (id, id_vendor, id_transaction, treatment, tax_object_code, payment_date, gross_amount, dpp_amount,
  tax_rate, tax_amount, settled_amount, net_amount, has_tax_id, rate_basis, payment_method, reference_number, created_at, updated_at)
VALUES ('be110000-0000-0000-0000-000000000001', 'be000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000003',
  'PPH_23', '24-104-03', '2025-06-10', 10000000, 10000000, 2, 200000, 10000000, 9800000, true, 'PPH23_STANDARD', 'TRANSFER', 'WHT-001', NOW(), NOW());

INSERT INTO project_payment_terms (id, id_project, sequence, name, percentage, due_trigger, auto_post, created_at, updated_at)
VALUES ('be120000-0000-0000-0000-000000000001', 'a0500000-0000-0000-0000-000000000001', 1, 'Uang Muka 30%', 30, 'ON_SIGNING', false, NOW(), NOW());

INSERT INTO alert_events (id, id_alert_rule, triggered_at, severity, message, details, acknowledged_at, acknowledged_by)
VALUES
('be130000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000006', '2025-06-14 09:00:00', 'WARNING', 'Saldo kas di bawah ambang', 'Kas: Rp 8.000.000', NULL, NULL),
('be130000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000007', '2025-06-01 09:00:00', 'INFO', 'Piutang jatuh tempo', 'INV-2024-001', '2025-06-02 10:00:00', 'admin');

-- The reconciliation report needs the bank account's ledger account.
UPDATE company_bank_accounts SET id_account = '10000000-0000-0000-0000-000000000102' WHERE id = 'b0600000-0000-0000-0000-000000000001';

INSERT INTO archive_documents (id, id_document, doc_year, doc_type, source, downloaded_on, doc_number, counterparty, amount, description,
  text_status, created_by, created_at, updated_at)
VALUES ('be140000-0000-0000-0000-000000000001', 'be0d0000-0000-0000-0000-000000000001', 2025, 'TAX_LETTER', 'TAX_PORTAL', '2025-06-05',
  'S-123/WPJ.04/2025', 'KPP Pratama Depok', 0, 'SP2DK PPN 2024', 'NO_TEXT', 'admin', NOW(), NOW());

-- The employee login's self-service pages need an employee record.
UPDATE employees SET id_user = 'b0000000-0000-0000-0000-000000000002' WHERE employee_id = 'EMP004';
