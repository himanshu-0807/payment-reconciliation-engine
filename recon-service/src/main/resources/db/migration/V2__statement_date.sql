-- Date a source file covers (from its object key). A bank statement with no credits still proves
-- that nothing arrived that day, so "as of" is the latest statement date, not the latest credit.
ALTER TABLE ingested_file ADD COLUMN statement_date DATE;
