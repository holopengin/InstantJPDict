// Copyright 2020 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#include "roialign_x86_avx.h"

#include "cpu.h"

namespace ncnn {

#include "roialign_fp32.h"

ROIAlign_x86_avx::ROIAlign_x86_avx()
{
}

int ROIAlign_x86_avx::forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const
{
    const Mat& bottom_blob = bottom_blobs[0];
    const Mat& roi_blob = bottom_blobs[1];

    Mat& top_blob = top_blobs[0];
    top_blob.create(pooled_width, pooled_height, bottom_blob.c, bottom_blob.elemsize, opt.blob_allocator);
    if (top_blob.empty())
        return -100;

    roialign_fp32(bottom_blob, roi_blob, top_blob, pooled_width, pooled_height, spatial_scale, sampling_ratio, aligned, version, opt);

    return 0;
}

} // namespace ncnn
