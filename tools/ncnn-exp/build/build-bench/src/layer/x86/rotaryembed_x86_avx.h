// Copyright 2025 pchar.cn
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_ROTARYEMBED_X86_AVX_H
#define LAYER_ROTARYEMBED_X86_AVX_H

#include "rotaryembed.h"

namespace ncnn {

class RotaryEmbed_x86_avx : public RotaryEmbed
{
public:
    RotaryEmbed_x86_avx();

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_bf16s(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_ROTARYEMBED_X86_AVX_H
