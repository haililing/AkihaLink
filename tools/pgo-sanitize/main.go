package main

import (
	"flag"
	"fmt"
	"os"

	"github.com/google/pprof/profile"
)

func main() {
	input := flag.String("input", "", "input CPU profile")
	output := flag.String("output", "", "sanitized output profile")
	flag.Parse()

	if *input == "" || *output == "" {
		fmt.Fprintln(os.Stderr, "-input and -output are required")
		os.Exit(2)
	}

	in, err := os.Open(*input)
	if err != nil {
		fatal(err)
	}
	p, err := profile.Parse(in)
	closeErr := in.Close()
	if err != nil {
		fatal(err)
	}
	if closeErr != nil {
		fatal(closeErr)
	}

	for _, mapping := range p.Mapping {
		mapping.File = ""
	}

	out, err := os.OpenFile(*output, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		fatal(err)
	}
	if err := p.Write(out); err != nil {
		_ = out.Close()
		fatal(err)
	}
	if err := out.Close(); err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
