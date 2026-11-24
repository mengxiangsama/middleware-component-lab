package presign

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

const bucket = "report-attachments"

func TestPresignedReportUpload(t *testing.T) {
	endpoint, credentials, serverExit, startupLog := startSeaweedFS(t)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	httpClient := &http.Client{
		Timeout:       3 * time.Second,
		Transport:     &http.Transport{},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	defer httpClient.CloseIdleConnections()
	store := s3.New(s3.Options{
		Region:                     "us-east-1",
		BaseEndpoint:               aws.String(endpoint),
		UsePathStyle:               true,
		Credentials:                aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) { return credentials, nil }),
		Retryer:                    aws.NopRetryer{},
		HTTPClient:                 httpClient,
		RequestChecksumCalculation: aws.RequestChecksumCalculationWhenRequired,
	})
	waitForBucket(t, ctx, store, serverExit, startupLog)

	const key = "reconciliation/day-report.csv"
	payload := []byte("date,orders\n2026-11-24,23\n")
	md5sum := md5.Sum(payload)
	contentMD5 := base64.StdEncoding.EncodeToString(md5sum[:])

	presigner := s3.NewPresignClient(store, func(options *s3.PresignOptions) {
		options.Presigner = v4.NewSigner(func(signer *v4.SignerOptions) {
			signer.DisableHeaderHoisting = true
		})
	})
	expireInMinute := func(options *s3.PresignOptions) { options.Expires = time.Minute }
	put, err := presigner.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(bucket),
		Key:           aws.String(key),
		ContentLength: aws.Int64(int64(len(payload))),
		ContentType:   aws.String("text/csv"),
		ContentMD5:    aws.String(contentMD5),
		Metadata:      map[string]string{"source": "reconciliation-lab"},
	}, expireInMinute)
	if err != nil {
		t.Fatalf("create presigned PUT: %v", err)
	}
	target, err := url.Parse(put.URL)
	if err != nil || target.Query().Get("X-Amz-Expires") != "60" {
		t.Fatal("expected a presigned URL with a 60-second expiry")
	}
	if put.SignedHeader.Get("X-Amz-Meta-Source") != "reconciliation-lab" ||
		put.SignedHeader.Get("Content-Type") != "text/csv" {
		t.Fatal("expected the object source and content type to be signed HTTP headers")
	}

	// The upload client only needs the URL, HTTP method, and signed headers.
	doTransfer(t, ctx, httpClient, put.Method, put.URL, put.SignedHeader, payload, http.StatusOK)

	head, err := store.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String(bucket), Key: aws.String(key)})
	if err != nil {
		t.Fatalf("head uploaded object: %v", err)
	}
	if aws.ToInt64(head.ContentLength) != int64(len(payload)) ||
		aws.ToString(head.ContentType) != "text/csv" ||
		head.Metadata["source"] != "reconciliation-lab" {
		t.Fatalf("stored object differs: size=%d, MIME type=%q, source=%q",
			aws.ToInt64(head.ContentLength), aws.ToString(head.ContentType), head.Metadata["source"])
	}

	get, err := presigner.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(bucket), Key: aws.String(key)}, expireInMinute)
	if err != nil {
		t.Fatalf("create presigned GET: %v", err)
	}
	downloaded := doTransfer(t, ctx, httpClient, get.Method, get.URL, get.SignedHeader, nil, http.StatusOK)
	if sha256.Sum256(downloaded) != sha256.Sum256(payload) {
		t.Fatal("downloaded report checksum differs")
	}

	// Reusing the signature with a different key must not grant write access.
	target.Path = "/" + bucket + "/reconciliation/another-report.csv"
	doTransfer(t, ctx, httpClient, put.Method, target.String(), put.SignedHeader, payload, http.StatusForbidden)
	doTransfer(t, ctx, httpClient, http.MethodGet, endpoint+"/"+bucket+"/"+key, nil, nil, http.StatusForbidden)

	t.Log("presigned PUT/GET succeeded; length, type, source metadata and SHA-256 matched")
	t.Log("changing the signed object's key and anonymous download both returned 403")
}

func doTransfer(t *testing.T, ctx context.Context, client *http.Client, method, target string, headers http.Header, payload []byte, wantStatus int) []byte {
	t.Helper()
	request, err := http.NewRequestWithContext(ctx, method, target, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("construct %s request", method)
	}
	if headers != nil {
		request.Header = headers.Clone()
	}
	response, err := client.Do(request)
	if err != nil {
		var requestError *url.Error
		if errors.As(err, &requestError) {
			err = requestError.Err // Avoid printing the presigned URL.
		}
		t.Fatalf("%s request failed: %v", method, err)
	}
	defer response.Body.Close()
	if response.StatusCode != wantStatus {
		t.Fatalf("%s returned %d, want %d", method, response.StatusCode, wantStatus)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 64*1024))
	if err != nil {
		t.Fatalf("read %s response: %v", method, err)
	}
	return body
}

func startSeaweedFS(t *testing.T) (string, aws.Credentials, <-chan error, func() string) {
	t.Helper()
	binary := os.Getenv("WEED_BINARY")
	if binary == "" {
		binary = "weed"
	}
	binary, err := exec.LookPath(binary)
	if err != nil {
		t.Fatal("set WEED_BINARY to the SeaweedFS 4.55 weed executable")
	}
	binary, err = filepath.Abs(binary)
	if err != nil {
		t.Fatal(err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}

	dir := t.TempDir()
	credentials := aws.Credentials{AccessKeyID: rand.Text(), SecretAccessKey: rand.Text()}
	logFile, err := os.Create(filepath.Join(dir, "weed.log"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = logFile.Close() })
	command := exec.Command(binary, "mini", "-dir="+dir, "-ip=127.0.0.1", "-ip.bind=127.0.0.1",
		"-bucket="+bucket, "-s3.port="+strconv.Itoa(port), "-s3.autoCreateBucket=false",
		"-s3.port.iceberg=0", "-s3.port.lance=0", "-admin.ui=false", "-webdav=false", "-master.telemetry=false")
	command.Dir = dir
	command.Env = []string{
		"HOME=" + dir,
		"TMPDIR=" + dir,
		"GOMAXPROCS=2",
		"AWS_ACCESS_KEY_ID=" + credentials.AccessKeyID,
		"AWS_SECRET_ACCESS_KEY=" + credentials.SecretAccessKey,
	}
	command.Stdout, command.Stderr = logFile, logFile
	if err := command.Start(); err != nil {
		t.Fatalf("start SeaweedFS: %v", err)
	}
	exit := make(chan error, 1)
	go func() {
		exit <- command.Wait()
		close(exit)
	}()
	t.Cleanup(func() {
		select {
		case <-exit:
			return
		default:
		}
		_ = command.Process.Signal(os.Interrupt)
		forced := time.AfterFunc(3*time.Second, func() { _ = command.Process.Kill() })
		defer forced.Stop()
		<-exit
	})
	startupLog := func() string {
		data, _ := os.ReadFile(logFile.Name())
		return strings.NewReplacer(credentials.AccessKeyID, "[redacted]", credentials.SecretAccessKey, "[redacted]").Replace(string(data))
	}
	return fmt.Sprintf("http://127.0.0.1:%d", port), credentials, exit, startupLog
}

func waitForBucket(t *testing.T, ctx context.Context, store *s3.Client, serverExit <-chan error, startupLog func() string) {
	t.Helper()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		_, err := store.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(bucket)})
		if err == nil {
			return
		}
		select {
		case err := <-serverExit:
			t.Fatalf("SeaweedFS exited before bucket was ready: %v\n%s", err, startupLog())
		case <-ctx.Done():
			t.Fatalf("SeaweedFS bucket not ready: %v\n%s", err, startupLog())
		case <-ticker.C:
		}
	}
}
