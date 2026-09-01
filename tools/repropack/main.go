package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

func main() {
	if len(os.Args) < 5 {
		fatalf("usage: repropack zip ROOT OUTPUT EPOCH | normalize-zip INPUT OUTPUT EPOCH | targz ROOT PREFIX OUTPUT EPOCH")
	}
	switch os.Args[1] {
	case "zip":
		if len(os.Args) != 5 {
			fatalf("usage: repropack zip ROOT OUTPUT EPOCH")
		}
		epoch := parseEpoch(os.Args[4])
		check(createZip(os.Args[2], os.Args[3], epoch))
	case "targz":
		if len(os.Args) != 6 {
			fatalf("usage: repropack targz ROOT PREFIX OUTPUT EPOCH")
		}
		epoch := parseEpoch(os.Args[5])
		check(createTarGz(os.Args[2], os.Args[3], os.Args[4], epoch))
	case "normalize-zip":
		if len(os.Args) != 5 {
			fatalf("usage: repropack normalize-zip INPUT OUTPUT EPOCH")
		}
		epoch := parseEpoch(os.Args[4])
		check(normalizeZip(os.Args[2], os.Args[3], epoch))
	default:
		fatalf("unsupported archive format %q", os.Args[1])
	}
}

func normalizeZip(input, output string, epoch time.Time) (returnErr error) {
	reader, err := zip.OpenReader(input)
	if err != nil {
		return err
	}
	defer reader.Close()
	sort.Slice(reader.File, func(i, j int) bool { return reader.File[i].Name < reader.File[j].Name })
	if err = os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return err
	}
	file, err := os.Create(output)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := file.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	writer := zip.NewWriter(file)
	defer func() {
		if closeErr := writer.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	for _, sourceHeader := range reader.File {
		if sourceHeader.FileInfo().IsDir() {
			continue
		}
		source, err := sourceHeader.Open()
		if err != nil {
			return err
		}
		header := &zip.FileHeader{
			Name:     filepath.ToSlash(sourceHeader.Name),
			Method:   sourceHeader.Method,
			Modified: epoch,
		}
		header.SetMode(0o644)
		destination, err := writer.CreateHeader(header)
		if err != nil {
			_ = source.Close()
			return err
		}
		_, copyErr := io.Copy(destination, source)
		closeErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func parseEpoch(value string) time.Time {
	seconds, err := strconv.ParseInt(value, 10, 64)
	if err != nil || seconds < 315532800 {
		fatalf("invalid SOURCE_DATE_EPOCH %q", value)
	}
	return time.Unix(seconds, 0).UTC()
}

type archiveEntry struct {
	path string
	name string
	info fs.FileInfo
}

func entries(root string, includeDirectories bool) ([]archiveEntry, error) {
	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	var result []archiveEntry
	err = filepath.Walk(absolute, func(path string, info fs.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == absolute {
			return nil
		}
		if info.IsDir() && !includeDirectories {
			return nil
		}
		relative, err := filepath.Rel(absolute, path)
		if err != nil {
			return err
		}
		name := filepath.ToSlash(relative)
		if strings.HasPrefix(name, "../") || name == ".." {
			return fmt.Errorf("archive input escaped root: %s", path)
		}
		if info.IsDir() {
			name += "/"
		}
		result = append(result, archiveEntry{path: path, name: name, info: info})
		return nil
	})
	sort.Slice(result, func(i, j int) bool { return result[i].name < result[j].name })
	return result, err
}

func createZip(root, output string, epoch time.Time) (returnErr error) {
	files, err := entries(root, false)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return err
	}
	file, err := os.Create(output)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := file.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	writer := zip.NewWriter(file)
	defer func() {
		if closeErr := writer.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	for _, entry := range files {
		header := &zip.FileHeader{
			Name:     entry.name,
			Method:   zip.Deflate,
			Modified: epoch,
		}
		header.SetMode(fileMode(entry.name))
		destination, err := writer.CreateHeader(header)
		if err != nil {
			return err
		}
		source, err := os.Open(entry.path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(destination, source)
		closeErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func createTarGz(root, prefix, output string, epoch time.Time) (returnErr error) {
	items, err := entries(root, true)
	if err != nil {
		return err
	}
	prefix = strings.Trim(strings.ReplaceAll(prefix, `\`, "/"), "/")
	if prefix == "" || strings.Contains(prefix, "..") {
		return fmt.Errorf("invalid archive prefix %q", prefix)
	}
	if err = os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return err
	}
	file, err := os.Create(output)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := file.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	gzipWriter, err := gzip.NewWriterLevel(file, gzip.BestCompression)
	if err != nil {
		return err
	}
	gzipWriter.Header.ModTime = epoch
	gzipWriter.Header.OS = 255
	defer func() {
		if closeErr := gzipWriter.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	tarWriter := tar.NewWriter(gzipWriter)
	defer func() {
		if closeErr := tarWriter.Close(); returnErr == nil {
			returnErr = closeErr
		}
	}()
	rootHeader := &tar.Header{
		Name:       prefix + "/",
		Typeflag:   tar.TypeDir,
		Mode:       0o755,
		ModTime:    epoch,
		AccessTime: epoch,
		ChangeTime: epoch,
		Format:     tar.FormatPAX,
	}
	if err = tarWriter.WriteHeader(rootHeader); err != nil {
		return err
	}
	for _, entry := range items {
		name := prefix + "/" + entry.name
		header := &tar.Header{
			Name:       name,
			ModTime:    epoch,
			AccessTime: epoch,
			ChangeTime: epoch,
			Uid:        0,
			Gid:        0,
			Uname:      "",
			Gname:      "",
			Format:     tar.FormatPAX,
		}
		if entry.info.IsDir() {
			header.Typeflag = tar.TypeDir
			header.Mode = 0o755
		} else {
			header.Typeflag = tar.TypeReg
			header.Mode = int64(fileMode(entry.name).Perm())
			header.Size = entry.info.Size()
		}
		if err = tarWriter.WriteHeader(header); err != nil {
			return err
		}
		if entry.info.IsDir() {
			continue
		}
		source, err := os.Open(entry.path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(tarWriter, source)
		closeErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func fileMode(name string) fs.FileMode {
	switch name {
	case "action.sh", "customize.sh", "service.sh", "uninstall.sh",
		"bin/akihalinkctl", "bin/sing-box", "bin/supervisor.sh":
		return 0o755
	}
	if strings.HasSuffix(name, ".sh") {
		return 0o755
	}
	return 0o644
}

func check(err error) {
	if err != nil {
		fatalf("%v", err)
	}
}

func fatalf(format string, values ...any) {
	_, _ = fmt.Fprintf(os.Stderr, format+"\n", values...)
	os.Exit(1)
}
