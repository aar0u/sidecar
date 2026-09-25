package main

import (
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

//go:embed web/*
var embeddedWebFS embed.FS

// OBE Projector BLE Protocol Constants
const (
	ServiceUUIDPrimary  = "0000fff0-0000-1000-8000-00805f9b34fb"
	ServiceUUIDFallback = "0000ffe0-0000-1000-8000-00805f9b34fb"
	CharWriteUUID       = "0000fff1-0000-1000-8000-00805f9b34fb"

	ActionPress   byte = 0x00
	ActionRelease byte = 0x01
)

// KeyCode Mapping Table (derived from reverse-engineered WeChat mini-program deploy.js)
var KeyCodes = map[string]byte{
	// Power / Standby
	"power": 26, // 0x1A
	"on":    26,
	"off":   26,

	// D-Pad Navigation
	"up":     19, // 0x13
	"top":    19,
	"down":   20, // 0x14
	"bottom": 20,
	"left":   21, // 0x15
	"right":  22, // 0x16
	"ok":     23, // 0x17
	"enter":  23,

	// System Navigation
	"home": 3,  // 0x03
	"back": 4,  // 0x04
	"menu": 82, // 0x52
	"list": 82,

	// Volume
	"vol_up":   24, // 0x18
	"vol_down": 25, // 0x19

	// Projector Dedicated Functions
	"focus":      131, // 0x83 (Auto Focus)
	"focus_long": 132, // 0x84 (Long Focus)
	"settings":   172, // 0xAC (Settings)
	"findme":     222, // 0xDE (Find Remote / Sound)
}

type Frame struct {
	Hex     string `json:"hex"`
	DelayMs int    `json:"delay_ms,omitempty"`
}

type FrameRequest struct {
	Key     string `json:"key"`
	Code    int    `json:"code"`
	HoldMs  int    `json:"hold_ms"`
	Repeat  int    `json:"repeat"`
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func generateKeyFrames(code byte, holdMs int) []Frame {
	if holdMs <= 0 {
		holdMs = 80
	}
	pressPayload := []byte{code, ActionPress}
	releasePayload := []byte{code, ActionRelease}

	return []Frame{
		{Hex: hex.EncodeToString(pressPayload), DelayMs: holdMs},
		{Hex: hex.EncodeToString(releasePayload), DelayMs: 0},
	}
}

func main() {
	port := flag.Int("port", 8081, "HTTP server listening port")
	host := flag.String("host", "0.0.0.0", "HTTP server listening address")
	flag.Parse()

	// Environment variable override (e.g. PORT=8081)
	if envPort := os.Getenv("PORT"); envPort != "" {
		var p int
		if _, err := fmt.Sscanf(envPort, "%d", &p); err == nil && p > 0 {
			*port = p
		}
	}

	mux := http.NewServeMux()

	// 1. Protocol Configuration API
	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"service_uuid_primary":  ServiceUUIDPrimary,
			"service_uuid_fallback": ServiceUUIDFallback,
			"char_write_uuid":       CharWriteUUID,
			"keys":                  KeyCodes,
		})
	})

	// 2. Service Status API
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"service":   "obe-remote",
			"status":    "running",
			"timestamp": time.Now().Unix(),
		})
	})

	// 3. Key Frame Generator API
	mux.HandleFunc("/api/frame", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		var req FrameRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON: " + err.Error()})
			return
		}

		code := byte(req.Code)
		if code == 0 && req.Key != "" {
			if mapped, ok := KeyCodes[strings.ToLower(req.Key)]; ok {
				code = mapped
			}
		}

		if code == 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Unknown or missing key code"})
			return
		}

		repeat := req.Repeat
		if repeat < 1 {
			repeat = 1
		}

		var allFrames []Frame
		for i := 0; i < repeat; i++ {
			frames := generateKeyFrames(code, req.HoldMs)
			allFrames = append(allFrames, frames...)
			if i < repeat-1 {
				allFrames[len(allFrames)-1].DelayMs = 100 // gap between repetitions
			}
		}

		writeJSON(w, http.StatusOK, map[string]interface{}{
			"service_uuid": ServiceUUIDPrimary,
			"char_uuid":    CharWriteUUID,
			"frames":       allFrames,
		})
	})

	// 4. Embedded Static Web Assets
	subFS, err := fs.Sub(embeddedWebFS, "web")
	if err != nil {
		log.Fatalf("Failed to create sub filesystem from embedded web: %v", err)
	}

	fileServer := http.FileServer(http.FS(subFS))
	mux.Handle("/", fileServer)

	addr := fmt.Sprintf("%s:%d", *host, *port)
	log.Printf("[OBE-Remote] Starting Go Service on http://%s\n", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}
