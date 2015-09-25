#if defined (_WIN32)

// TODO ATN

#include "arch/runtime/event_queue/windows.hpp"
#include "arch/runtime/thread_pool.hpp"
#include "arch/io/event_watcher.hpp"

windows_event_queue_t::windows_event_queue_t(linux_thread_t *thread_)
    : thread(thread_) {
    completion_port = CreateIoCompletionPort(INVALID_HANDLE_VALUE, NULL, static_cast<ULONG_PTR>(windows_message_type_t::ASYNC_OPERATION), 1);
    guarantee_winerr(completion_port != NULL, "CreateIoCompletionPort failed");
}

void windows_event_queue_t::add_handle(fd_t handle) {
    completion_port = CreateIoCompletionPort(handle, completion_port, static_cast<ULONG_PTR>(windows_message_type_t::ASYNC_OPERATION), 1);
    guarantee_winerr(completion_port != NULL, "CreateIoCompletionPort failed");
}

void windows_event_queue_t::watch_event(windows_event_t& event, event_callback_t *cb) {
    rassert(event.event_queue == nullptr && event.callback == nullptr, "Cannot watch the same event twice"); 
    event.callback = cb;
    event.event_queue = this;
}

void windows_event_queue_t::forget_event(windows_event_t& event, event_callback_t *cb) {
    if (event.event_queue != nullptr) {
        event.event_queue = nullptr;
    }
    event.callback = nullptr;
}

void windows_event_queue_t::post_event(event_callback_t *cb) {
    rassert(cb != nullptr);
    BOOL res = PostQueuedCompletionStatus(completion_port, 0, ULONG_PTR(windows_message_type_t::EVENT_CALLBACK), reinterpret_cast<OVERLAPPED*>(cb));
    guarantee_winerr(res, "PostQueuedCompletionStatus failed");
}

void windows_event_queue_t::run() {
    while (!thread->should_shut_down()) {
        ULONG nb_bytes;
        ULONG_PTR key;
        OVERLAPPED *overlapped;

        BOOL res = GetQueuedCompletionStatus(completion_port, &nb_bytes, &key, &overlapped, INFINITE);
        DWORD error = res ? NO_ERROR : GetLastError();
        if (overlapped == NULL) {
            guarantee_winerr(res != 0, "GetQueuedCompletionStatus failed");
        }

        switch (key) {
        case windows_message_type_t::ASYNC_OPERATION: {
            async_operation_t *ao = reinterpret_cast<async_operation_t*>(overlapped);
            ao->set_result(nb_bytes, error);
            break;
        }

        case windows_message_type_t::EVENT_CALLBACK: {
            event_callback_t *cb = reinterpret_cast<event_callback_t*>(overlapped);
            cb->on_event(poll_event_in); // TODO ATN: what value does on_event expect?
            break;
        }

        default:
            crash("Unknown message type in IOCP queue %ld");
        }

        thread->pump();
    }
}

#endif