package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/** The pieces of an S3 request that have to match what the store computes byte for byte. */
class S3ClientTest {

    @Test
    fun `a request is signed as the AWS documentation's example is`() {
        // The GET example in "Signature Calculations for the Authorization Header" of the S3 API reference.
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val header = S3Client.authorization(
            method = "GET",
            host = "examplebucket.s3.amazonaws.com",
            path = "/test.txt",
            query = "",
            headers = mapOf("range" to "bytes=0-9", "x-amz-content-sha256" to emptyHash, "x-amz-date" to "20130524T000000Z"),
            payloadHash = emptyHash,
            amzDate = "20130524T000000Z",
            region = "us-east-1",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            header,
        )
    }

    @Test
    fun `names are escaped the way S3 signs them`() {
        assertEquals("a%20b%2F%C3%BC~", S3Client.encode("a b/ü~"))
        assertEquals("AZaz09-_.~", S3Client.encode("AZaz09-_.~"))
        assertEquals("%2B%3D%26%2A", S3Client.encode("+=&*"))
    }

    @Test
    fun `the default port is left out of the address and the host`() {
        val https = S3Client.normalize(URI("https://store.example.com:443"))
        assertEquals("store.example.com", S3Client.hostHeader(https))
        val local = S3Client.normalize(URI("http://127.0.0.1:9000"))
        assertEquals("127.0.0.1:9000", S3Client.hostHeader(local))
        assertEquals("store.example.com", S3Client.hostHeader(S3Client.normalize(URI("http://store.example.com:80"))))
    }
}
