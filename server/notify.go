package main

import "sync"

// notifier wakes long-polling clients when their family gets a new message.
// Each family has a channel that is closed (and replaced) on every new message.
type notifier struct {
	mu     sync.Mutex
	chans  map[string]chan struct{}
	closed bool
}

func newNotifier() *notifier { return &notifier{chans: map[string]chan struct{}{}} }

// Wait returns a channel that is closed on the next notification for the family.
func (n *notifier) Wait(familyID string) <-chan struct{} {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		c := make(chan struct{})
		close(c)
		return c
	}
	c, ok := n.chans[familyID]
	if !ok {
		c = make(chan struct{})
		n.chans[familyID] = c
	}
	return c
}

func (n *notifier) Notify(familyID string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if c, ok := n.chans[familyID]; ok {
		close(c)
		delete(n.chans, familyID)
	}
}

// CloseAll releases every waiter (used on shutdown).
func (n *notifier) CloseAll() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.closed = true
	for id, c := range n.chans {
		close(c)
		delete(n.chans, id)
	}
}
