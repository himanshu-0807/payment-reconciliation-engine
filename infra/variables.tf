variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "localstack_endpoint" {
  description = "LocalStack edge endpoint"
  type        = string
  default     = "http://localhost:4566"
}

variable "bucket_name" {
  type    = string
  default = "recon-data-tf"
}

variable "ground_truth_retention_days" {
  type    = number
  default = 30
}
