const hre = require("hardhat");

async function main() {
  console.log("Deploying TimberDemo contract...");

  const TimberDemo = await hre.ethers.getContractFactory("TimberDemo");
  const timberDemo = await TimberDemo.deploy();

  await timberDemo.waitForDeployment();

  const address = await timberDemo.getAddress();
  console.log("TimberDemo deployed to:", address);

  // Save deployment info
  const fs = require('fs');
  const deploymentInfo = {
    address: address,
    network: hre.network.name,
    timestamp: new Date().toISOString()
  };

  fs.writeFileSync('deployment.json', JSON.stringify(deploymentInfo, null, 2));
  console.log("Deployment info saved to deployment.json");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});