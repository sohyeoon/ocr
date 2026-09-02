package com.filesearch.processor;

import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;

public interface FileProcessor {
	
	boolean supports(String extension);

    FileProcessResult process(FileProcessRequest request) throws Exception;
}
