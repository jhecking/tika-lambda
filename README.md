Run [Apache Tika](https://tika.apache.org) as a service in AWS Lambda by
scanning documents in S3 and storing the extracted text back to S3.

Based on original Tika Lambda version in
[gnethercutt/tika-lambda](https://github.com/gnethercutt/tika-lambda) as well
as subsequent enhancements in
[DovetailSoftware/tika-lambda](https://github.com/DovetailSoftware/tika-lambda)
as well as [cmaxwellau/tika-lambda](https://github.com/cmaxwellau/tika-lambda).

This version of the Tika Lambda function adds:
* An AWS Serverless Application Model template to easily package & deploy the function.
* A Gradle build file to build, package & deploy the application.
* Configurable S3 bucket prefix & extension for extracted data.
