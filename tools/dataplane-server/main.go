package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"strings"
	"time"
)

func main() {
	tcpAddress := flag.String("tcp", ":5201", "TCP benchmark listen address")
	udpAddress := flag.String("udp", ":5202", "UDP echo listen address")
	flag.Parse()
	errors := make(chan error, 2)
	go func() { errors <- serveTCP(*tcpAddress) }()
	go func() { errors <- serveUDP(*udpAddress) }()
	log.Fatal(<-errors)
}

func serveTCP(address string) error {
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	log.Printf("TCP benchmark server listening on %s", listener.Addr())
	for {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return acceptErr
		}
		go handleTCP(connection)
	}
}

func handleTCP(connection net.Conn) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(10 * time.Minute))
	reader := bufio.NewReader(connection)
	command, err := reader.ReadString('\n')
	if err != nil {
		return
	}
	fields := strings.Fields(command)
	if len(fields) == 0 {
		return
	}
	switch fields[0] {
	case "PING":
		_, _ = fmt.Fprintln(connection, "PONG")
	case "DOWNLOAD":
		if len(fields) != 2 {
			return
		}
		remaining, parseErr := strconv.ParseInt(fields[1], 10, 64)
		if parseErr != nil || remaining <= 0 || remaining > 1<<40 {
			return
		}
		block := make([]byte, 128<<10)
		for remaining > 0 {
			count := int64(len(block))
			if count > remaining {
				count = remaining
			}
			if _, err = connection.Write(block[:count]); err != nil {
				return
			}
			remaining -= count
		}
	case "UPLOAD":
		_, _ = io.Copy(io.Discard, reader)
	default:
		_, _ = fmt.Fprintln(connection, "invalid command")
	}
}

func serveUDP(address string) error {
	connection, err := net.ListenPacket("udp", address)
	if err != nil {
		return err
	}
	log.Printf("UDP benchmark server listening on %s", connection.LocalAddr())
	buffer := make([]byte, 64<<10)
	for {
		count, source, readErr := connection.ReadFrom(buffer)
		if readErr != nil {
			return readErr
		}
		payload := append([]byte(nil), buffer[:count]...)
		go func() {
			_, _ = connection.WriteTo(payload, source)
		}()
	}
}
