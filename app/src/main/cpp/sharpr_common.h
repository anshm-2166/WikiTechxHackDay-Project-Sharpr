// Shared bits of the Sharpr native core: return codes and progress/cancel plumbing.
#pragma once

namespace sharpr {

enum Rc {
    RC_OK = 0,
    RC_CANCELLED = 1,
    RC_BAD_INPUT = -1,
    RC_MODEL_LOAD = -2,
    RC_INFERENCE = -3,
    RC_NO_GPU = -4,
    RC_IO = -5,
};

class ProgressSink {
public:
    virtual ~ProgressSink() = default;
    virtual void progress(int done, int total) {}
    virtual bool cancelled() { return false; }
};

}  // namespace sharpr
