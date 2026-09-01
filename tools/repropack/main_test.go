package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestZipIsDeterministicAndSorted(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string]string{
		"module.prop":      "version=0.8.0\n",
		"bin/akihalinkctl": "#!/system/bin/sh\n",
	} {
		if err := os.WriteFile(filepath.Join(root, filepath.FromSlash(name)), []byte(value), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	epoch := time.Unix(1_700_000_000, 0).UTC()
	first := filepath.Join(t.TempDir(), "first.zip")
	second := filepath.Join(t.TempDir(), "second.zip")
	if err := createZip(root, first, epoch); err != nil {
		t.Fatal(err)
	}
	if err := createZip(root, second, epoch); err != nil {
		t.Fatal(err)
	}
	firstBytes, _ := os.ReadFile(first)
	secondBytes, _ := os.ReadFile(second)
	if sha256.Sum256(firstBytes) != sha256.Sum256(secondBytes) {
		t.Fatal("identical inputs produced different archives")
	}
	reader, err := zip.OpenReader(first)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if len(reader.File) != 2 ||
		reader.File[0].Name != "bin/akihalinkctl" ||
		reader.File[1].Name != "module.prop" {
		t.Fatalf("unexpected entry order: %#v", reader.File)
	}
	if reader.File[0].Mode().Perm() != 0o755 || reader.File[1].Mode().Perm() != 0o644 {
		t.Fatalf(
			"unexpected normalized modes: %o %o",
			reader.File[0].Mode().Perm(),
			reader.File[1].Mode().Perm(),
		)
	}
}

func TestNormalizeZipRemovesHostMetadata(t *testing.T) {
	input := filepath.Join(t.TempDir(), "input.zip")
	file, err := os.Create(input)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	header := &zip.FileHeader{Name: "classes.dex", Method: zip.Store}
	header.Modified = time.Now()
	header.Extra = []byte{1, 2, 3, 4}
	entry, err := writer.CreateHeader(header)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("dex"))
	_ = writer.Close()
	_ = file.Close()

	epoch := time.Unix(1_700_000_000, 0).UTC()
	output := filepath.Join(t.TempDir(), "output.zip")
	if err = normalizeZip(input, output, epoch); err != nil {
		t.Fatal(err)
	}
	reader, err := zip.OpenReader(output)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if len(reader.File) != 1 ||
		bytes.Equal(reader.File[0].Extra, []byte{1, 2, 3, 4}) ||
		reader.File[0].Modified.Unix() != epoch.Unix() {
		t.Fatalf("unexpected normalized ZIP metadata: %#v", reader.File)
	}
}
