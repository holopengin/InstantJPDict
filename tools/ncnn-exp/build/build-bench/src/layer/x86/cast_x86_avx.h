// Copyright 2019 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_CAST_X86_AVX_H
#define LAYER_CAST_X86_AVX_H

#include "cast.h"

namespace ncnn {

class Cast_x86_avx : public Cast
{
public:
    Cast_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_CAST_X86_AVX_H
