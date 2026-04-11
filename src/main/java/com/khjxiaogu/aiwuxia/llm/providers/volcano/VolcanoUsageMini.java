/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.llm.providers.volcano;

public class VolcanoUsageMini extends VolcanoUsage {

	@Override
	public double getEquivantTokens() {//0.6 0.9 1.8 /3.6 5.4 10.8
		long uncached=prompt_tokens-prompt_tokens_details.cached_tokens;
		long cached=prompt_tokens_details.cached_tokens;
		return completion_tokens*1d+cached*0.02d+uncached*0.1d;
	}
    public void zoomEquivantly() {
    	if(prompt_tokens>128000) {
    		total_tokens*=4;
    		prompt_tokens*=4;
    		completion_tokens*=4;
    		completion_tokens_details.multiply(4f);
    		prompt_tokens_details.multiply(4f);
    	}else if(prompt_tokens>32000) {
    		total_tokens*=2;
    		prompt_tokens*=2;
    		completion_tokens*=2;
    		completion_tokens_details.multiply(2);
    		prompt_tokens_details.multiply(2);
    	}
    	
    		
    }
}