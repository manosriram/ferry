//go:build bundle

package main

import (
	"embed"
	"io/fs"
)

// webdist is a copy of ../web made by the release build (go:embed can't reach outside the module).
//
//go:embed all:webdist
var webdist embed.FS

func init() { bundledWeb, _ = fs.Sub(webdist, "webdist") }
