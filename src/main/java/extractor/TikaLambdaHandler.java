package extractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.net.URLDecoder;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

public class TikaLambdaHandler implements RequestHandler<S3Event, String> {

    private LambdaLogger _logger;

    private String sourcePrefix;
    private String targetPrefix;
    private String targetExt;

    public TikaLambdaHandler() {
      this.sourcePrefix = System.getenv("S3_SOURCE_PREFIX");
      this.targetPrefix = System.getenv("S3_TARGET_PREFIX");
      this.targetExt = System.getenv("S3_TARGET_EXT");
    }

    public String handleRequest(S3Event s3event, Context context) {
        _logger = context.getLogger();
        _logger.log("Received S3 Event: " + s3event.toJson());

        try {
            S3EventNotificationRecord record = s3event.getRecords().get(0);
            String bucket = record.getS3().getBucket().getName();
            // Object key may have spaces or unicode non-ASCII characters.
            String key = URLDecoder.decode(record.getS3().getObject().getKey().replace('+', ' '), "UTF-8");

            // Short-circuit ignore .extract files because they have already been extracted, this prevents an endless loop
            if (key.endsWith(this.targetExt)) {
              _logger.log("Ignoring extract file " + key);
              return "Ignored";
            }

            AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
            S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));

            try (InputStream objectData = s3Object.getObjectContent()) {
                TextExtractor extractor = new TextExtractor(_logger);
                String extractJson = extractor.extract(bucket, key, objectData);

                byte[] extractBytes = extractJson.getBytes(Charset.forName("UTF-8"));
                int extractLength = extractBytes.length;

                ObjectMetadata metaData = new ObjectMetadata();
                metaData.setContentLength(extractLength);

                InputStream inputStream = new ByteArrayInputStream(extractBytes);

                String targetKey = key.replaceFirst(this.sourcePrefix, this.targetPrefix);
                targetKey += this.targetExt;
                _logger.log("Saving extract file to S3 at s3://" + bucket + "/" + targetKey);
                s3Client.putObject(bucket, targetKey, inputStream, metaData);
            }
        } catch (IOException e) {
            _logger.log("Exception: " + e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
        return "Success";
    }
}