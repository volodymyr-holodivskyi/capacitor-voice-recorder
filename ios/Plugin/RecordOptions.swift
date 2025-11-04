import Foundation

struct RecordOptions {

    let directory: String?
    var subDirectory: String?
    let stopOnSilence: Bool?

    mutating func setSubDirectory(to subDirectory: String) {
      self.subDirectory = subDirectory
    }

}
