package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// TestCrossing walks the whole flow: fleet -> boarding code -> dock -> ferry -> download -> ack -> cleanup.
func TestCrossing(t *testing.T) {
	s, err := newServer(t.TempDir(), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(s.routes())
	defer ts.Close()

	call := func(method, path, token, body string, want int) []byte {
		t.Helper()
		req, _ := http.NewRequest(method, ts.URL+path, strings.NewReader(body))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode != want {
			t.Fatalf("%s %s: got %d want %d: %s", method, path, res.StatusCode, want, b)
		}
		return b
	}
	var a, b struct{ DeviceID, Token string }
	parse := func(raw []byte, v *struct{ DeviceID, Token string }) {
		var m map[string]string
		json.Unmarshal(raw, &m)
		v.DeviceID, v.Token = m["device_id"], m["token"]
	}

	parse(call("POST", "/api/fleet", "", `{"name":"MacBook","platform":"web"}`, 200), &a)
	var code struct{ Code string }
	json.Unmarshal(call("POST", "/api/dock/code", a.Token, "", 200), &code)
	call("POST", "/api/dock/claim", "", `{"code":"000000x"}`, 404)
	parse(call("POST", "/api/dock/claim", "", `{"name":"Pixel","platform":"android","code":"`+code.Code+`"}`, 200), &b)
	call("POST", "/api/dock/claim", "", `{"code":"`+code.Code+`"}`, 404) // single use

	var docks []dockJSON
	json.Unmarshal(call("GET", "/api/docks", a.Token, "", 200), &docks)
	if len(docks) != 2 {
		t.Fatalf("want 2 docks, got %+v", docks)
	}

	call("POST", "/api/crossings?name=../../etc/x.txt&to=nope", a.Token, "hi", 400)
	call("POST", "/api/crossings?name=x&to="+b.DeviceID, a.Token, strings.Repeat("x", 2<<20), 413)
	var up struct{ ID string }
	json.Unmarshal(call("POST", "/api/crossings?name=../../etc/x.txt&to="+b.DeviceID, a.Token, "hello ferry", 200), &up)

	var in []crossingJSON
	json.Unmarshal(call("GET", "/api/crossings", b.Token, "", 200), &in)
	if len(in) != 1 || in[0].Name != "x.txt" || in[0].From != "MacBook" || in[0].Status != "pending" {
		t.Fatalf("arrivals: %+v", in)
	}
	call("GET", "/api/crossings/"+up.ID+"/file", a.Token, "", 404) // sender is not a destination
	if got := string(call("GET", "/api/crossings/"+up.ID+"/file", b.Token, "", 200)); got != "hello ferry" {
		t.Fatalf("file body %q", got)
	}
	call("POST", "/api/crossings/"+up.ID+"/ack", b.Token, "", 204)
	call("GET", "/api/crossings/"+up.ID+"/file", b.Token, "", 200) // still saveable until expiry
	var out []crossingJSON
	json.Unmarshal(call("GET", "/api/crossings?box=out", a.Token, "", 200), &out)
	if len(out) != 1 || out[0].Targets["Pixel"] != "arrived" {
		t.Fatalf("departures: %+v", out)
	}

	s.sweep(time.Now().Add(transferTTL + time.Minute))
	json.Unmarshal(call("GET", "/api/crossings", b.Token, "", 200), &in)
	if len(in) != 0 {
		t.Fatalf("expired crossing still listed: %+v", in)
	}
	if _, err := os.Stat(s.blobPath(up.ID)); !os.IsNotExist(err) {
		t.Fatal("blob should be deleted on expiry")
	}

	call("DELETE", "/api/docks/"+b.DeviceID, a.Token, "", 204)
	call("GET", "/api/docks", b.Token, "", 401)
}
