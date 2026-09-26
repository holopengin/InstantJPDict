// Copyright 2023 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SHUFFLECHANNEL_X86_AVX_H
#define LAYER_SHUFFLECHANNEL_X86_AVX_H

#include "shufflechannel.h"

namespace ncnn {

class ShuffleChannel_x86_avx : public ShuffleChannel
{
public:
    ShuffleChannel_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_SHUFFLECHANNEL_X86_AVX_H
