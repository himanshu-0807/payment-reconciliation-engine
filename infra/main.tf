# S3 bucket for settlement files, bank statements and ground truth.
# Only ever validated and planned against LocalStack; never applied to real AWS.
#   terraform -chdir=infra init && terraform -chdir=infra validate && terraform -chdir=infra plan
terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region     = var.region
  access_key = "test" # LocalStack placeholder, not a credential
  secret_key = "test" # LocalStack placeholder, not a credential

  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    s3  = var.localstack_endpoint
    sts = var.localstack_endpoint
  }
}

resource "aws_s3_bucket" "recon_data" {
  bucket        = var.bucket_name
  force_destroy = true
  tags = {
    project = "payment-reconciliation-engine"
    env     = "local"
  }
}

resource "aws_s3_bucket_versioning" "recon_data" {
  bucket = aws_s3_bucket.recon_data.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "recon_data" {
  bucket                  = aws_s3_bucket.recon_data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "recon_data" {
  bucket = aws_s3_bucket.recon_data.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "recon_data" {
  bucket = aws_s3_bucket.recon_data.id
  rule {
    id     = "expire-ground-truth"
    status = "Enabled"
    filter {
      prefix = "ground-truth/"
    }
    expiration {
      days = var.ground_truth_retention_days
    }
  }
}
