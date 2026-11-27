package main

import (
	"os"
	"testing"
)

// isCritical 是强制停止的最后一道闸，必须挡住关键进程。
func TestIsCritical(t *testing.T) {
	if !isCritical(1) {
		t.Fatal("pid 1 必须受保护")
	}
	if !isCritical(os.Getpid()) {
		t.Fatal("服务自身必须受保护")
	}
}
