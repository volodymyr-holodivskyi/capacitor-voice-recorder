package com.tchvu3.capacitorvoicerecorder;

public class RecordOptions {

    private String directory;
    private String subDirectory;

    private Boolean stopOnSilence;

    public RecordOptions(String directory, String subDirectory, Boolean stopOnSilence) {
        this.directory = directory;
        this.subDirectory = subDirectory;
        this.stopOnSilence = stopOnSilence;
    }

    public String getDirectory() {
        return directory;
    }

    public String getSubDirectory() {
        return subDirectory;
    }

    public Boolean getStopOnSilence() { return stopOnSilence; }

    public void setSubDirectory(String subDirectory) {
        this.subDirectory = subDirectory;
    }
}
