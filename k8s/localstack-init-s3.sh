#!/bin/sh
# LocalStack ready hook: create the bucket used for settlement files, statements and ground truth.
awslocal s3 mb "s3://${S3_BUCKET:-recon-data}" 2>/dev/null || true
echo "bucket ${S3_BUCKET:-recon-data} ready"
