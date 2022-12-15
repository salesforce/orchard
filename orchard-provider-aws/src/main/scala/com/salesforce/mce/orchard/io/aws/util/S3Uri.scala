package com.salesforce.mce.orchard.io.aws.util

case class S3Uri private (bucket: String, path: String)

object S3Uri {

  val BucketRegex = raw"[a-z0-9\.\-]+"

  val UriRegex = raw"s3://($BucketRegex)/(.*)$$".r

  val UriBucketOnlyRegex = raw"s3://($BucketRegex)$$".r

  def apply(raw: String): S3Uri = raw match {
    case UriRegex(bucket, path) =>
      new S3Uri(bucket, path)
    case UriBucketOnlyRegex(bucket) =>
      new S3Uri(bucket, "")
    case _ =>
      throw new RuntimeException(s"$raw is an invalid s3 uri")
  }
}
